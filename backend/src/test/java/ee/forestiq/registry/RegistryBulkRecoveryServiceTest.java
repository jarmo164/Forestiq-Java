package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RegistryBulkRecoveryServiceTest {
    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CODE = "10001:001:0001";
    private static final String REGISTRY = "METSAREGISTER_ERALDISED";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private final ObjectMapper json = new ObjectMapper();
    private DriverManagerDataSource dataSource;
    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        dsl.execute("DROP SCHEMA public CASCADE");
        dsl.execute("CREATE SCHEMA public");
        dsl.execute("CREATE TABLE organization(id uuid primary key)");
        dsl.execute("""
            CREATE TABLE land_parcel(
                id uuid primary key, organization_id uuid not null, cadastral_code varchar(32) not null, mk_date bigint)
            """);
        dsl.execute("""
            CREATE TABLE owner_parcel_relation(
                organization_id uuid not null, owner_id uuid not null, parcel_id uuid not null, active boolean not null)
            """);
        dsl.execute("""
            CREATE TABLE cadastre_notification(
                id bigserial primary key, organization_id uuid not null, cadastral_code varchar(32) not null,
                notification_number bigint, work_code varchar(32), decision varchar(512), area numeric(12,4),
                archived boolean not null default false)
            """);
        dsl.execute("""
            CREATE TABLE forest_registry_feature(
                id bigserial primary key, organization_id uuid not null, cadastral_code varchar(32) not null,
                source_layer varchar(120), source_id varchar(120), title varchar(300), work_code varchar(32),
                decision varchar(512), area numeric(12,4), volume numeric(12,4), attributes text)
            """);
        dsl.execute("""
            CREATE TABLE registry_sync_log(
                id uuid primary key, organization_id uuid not null, cadastral_code varchar(32), registry_name varchar(64),
                request_url text, status varchar(32), records_updated int, error_message varchar(2000), raw_response jsonb,
                synced_by_subject varchar(120), created_at timestamptz not null default current_timestamp)
            """);
        dsl.execute("""
            CREATE TABLE data_change_signal(
                id uuid primary key, organization_id uuid not null, owner_id uuid not null, parcel_id uuid,
                signal_type varchar(64), source_registry varchar(64), source_key varchar(300), summary text,
                payload jsonb, created_by_subject varchar(120), dismissed_at timestamptz,
                UNIQUE(organization_id, owner_id, signal_type, source_registry, source_key))
            """);
        dsl.execute("""
            CREATE TABLE registry_bulk_sync_checkpoint(
                organization_id uuid not null, registry_name varchar(64) not null, batch_id uuid not null,
                cadastral_codes jsonb not null, next_start_index bigint not null default 0,
                pages_processed int not null default 0, records_staged bigint not null default 0,
                started_at timestamptz not null default current_timestamp, last_failure_at timestamptz,
                last_error varchar(500), updated_at timestamptz not null default current_timestamp,
                PRIMARY KEY(organization_id, registry_name))
            """);
        dsl.execute("""
            CREATE TABLE registry_bulk_sync_feature(
                batch_id uuid not null, organization_id uuid not null, registry_name varchar(64) not null,
                cadastral_code varchar(32) not null, feature_key varchar(160) not null, source_id varchar(120),
                title varchar(300), work_code varchar(32), decision varchar(512), area numeric(12,4),
                volume numeric(12,4), source_hash char(64) not null,
                PRIMARY KEY(batch_id, cadastral_code, feature_key))
            """);
        dsl.execute("INSERT INTO organization(id) VALUES (?)", ORG_ID);
        dsl.execute("INSERT INTO land_parcel(id,organization_id,cadastral_code) VALUES (?,?,?)", PARCEL_ID, ORG_ID, CODE);
        dsl.execute("""
            INSERT INTO forest_registry_feature(organization_id,cadastral_code,source_layer,source_id,title,attributes)
            VALUES (?,?,?,?,?,?)
            """, ORG_ID, CODE, REGISTRY, "old", "last-known-good", "{\"sourceHash\":\"old\"}");
    }

    @Test
    void pageCapKeepsLiveSnapshotAndNextRunResumesThenAppliesWholeBatch() {
        RegistryProperties properties = new RegistryProperties();
        properties.getMetsaregister().setBulkBatchSize(10);
        properties.getMetsaregister().setBulkPagesPerRun(1);
        properties.getMetsaregister().setMinRequestIntervalMs(0);
        RegistryPayloadPolicy payloadPolicy = new RegistryPayloadPolicy(json, properties);
        RegistrySyncAuditRepository audit = new RegistrySyncAuditRepository(dsl, payloadPolicy, properties);
        RegistryDataRepository data = new RegistryDataRepository(dsl, payloadPolicy);
        MetsaregisterWfsClient client = mock(MetsaregisterWfsClient.class);
        when(client.fetchPage(eq("metsaregister:eraldis"), anyString(), eq(0L)))
            .thenReturn(page(0, 2, false, feature(1), feature(2)));
        when(client.fetchPage(eq("metsaregister:eraldis"), anyString(), eq(2L)))
            .thenReturn(page(2, 3, true, feature(3)));
        RegistryBulkRecoveryService service = new RegistryBulkRecoveryService(
            dsl, client, properties, payloadPolicy, audit, data, json,
            new DataSourceTransactionManager(dataSource), new SimpleMeterRegistry());
        List<RegistryDataRepository.ParcelRef> stale = List.of(new RegistryDataRepository.ParcelRef(ORG_ID, CODE));

        var first = service.recover(ORG_ID, REGISTRY, stale);

        assertThat(first.completed()).isFalse();
        assertThat(first.pages()).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM forest_registry_feature WHERE cadastral_code=?", Integer.class, CODE))
            .isEqualTo(1);
        assertThat(value("SELECT source_id FROM forest_registry_feature WHERE cadastral_code=?", String.class, CODE))
            .isEqualTo("old");
        assertThat(value("SELECT next_start_index FROM registry_bulk_sync_checkpoint WHERE organization_id=? AND registry_name=?",
            Long.class, ORG_ID, REGISTRY)).isEqualTo(2L);
        assertThat(value("SELECT count(*) FROM registry_bulk_sync_feature", Integer.class)).isEqualTo(2);

        var second = service.recover(ORG_ID, REGISTRY, List.of());

        assertThat(second.completed()).isTrue();
        assertThat(value("SELECT count(*) FROM forest_registry_feature WHERE cadastral_code=?", Integer.class, CODE))
            .isEqualTo(3);
        assertThat(dsl.fetch("SELECT source_id FROM forest_registry_feature WHERE cadastral_code=? ORDER BY source_id", CODE)
            .getValues(0, String.class)).containsExactly("1", "2", "3");
        assertThat(value("SELECT count(*) FROM registry_bulk_sync_checkpoint", Integer.class)).isZero();
        assertThat(value("SELECT count(*) FROM registry_bulk_sync_feature", Integer.class)).isZero();
        assertThat(value("SELECT count(*) FROM registry_sync_log WHERE registry_name=? AND status='SUCCESS'",
            Integer.class, REGISTRY)).isEqualTo(1);
        verify(client).fetchPage(eq("metsaregister:eraldis"), anyString(), eq(0L));
        verify(client).fetchPage(eq("metsaregister:eraldis"), anyString(), eq(2L));
    }

    private <T> T value(String sql, Class<T> type, Object... bindings) {
        var record = dsl.fetchOne(sql, bindings);
        return record == null ? null : record.get(0, type);
    }

    private MetsaregisterWfsClient.PageResult page(long start, long next, boolean complete, ObjectNode... features) {
        ArrayNode values = json.createArrayNode();
        for (ObjectNode feature : features) values.add(feature);
        ObjectNode response = json.createObjectNode();
        response.put("numberMatched", 3);
        response.put("numberReturned", features.length);
        response.set("features", values);
        return new MetsaregisterWfsClient.PageResult(start, next, features.length, 3, complete,
            "https://example.test/wfs?startIndex=" + start, response, values);
    }

    private ObjectNode feature(long sourceId) {
        ObjectNode feature = json.createObjectNode();
        ObjectNode properties = feature.putObject("properties");
        properties.put("katastri_nr", CODE);
        properties.put("sys_id", sourceId);
        properties.put("eraldise_nr", "E" + sourceId);
        properties.put("pindala", sourceId);
        return feature;
    }
}
