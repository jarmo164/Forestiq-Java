package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MetsaregisterWfsMirrorServiceIntegrationTest {

    private static final String LAYER = "metsaregister:eraldis";
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private final ObjectMapper json = new ObjectMapper();
    private DSLContext dsl;
    private RegistryProperties properties;
    private DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource physical = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        transactionManager = new DataSourceTransactionManager(physical);
        dsl = DSL.using(new TransactionAwareDataSourceProxy(physical), SQLDialect.POSTGRES);

        dsl.execute("DELETE FROM metsaregister_wfs_feature");
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_generation_id=NULL, current_row_count=0");
        dsl.execute("DELETE FROM metsaregister_wfs_generation");
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("approved"), Boolean.class), true)
            .set(field(name("enabled"), Boolean.class), true)
            .set(field(name("provider_present"), Boolean.class), true)
            .set(field(name("state"), String.class), "READY")
            .set(field(name("identity_property"), String.class), "sys_id")
            .set(field(name("sort_property"), String.class), "sys_id")
            .set(field(name("last_error"), String.class), (String) null)
            .where(field(name("layer_name"), String.class).eq(LAYER))
            .execute();

        properties = new RegistryProperties();
        properties.getMetsaregister().setWfsUrl("https://example.test/geoserver/metsaregister/ows");
        properties.getMetsaregister().setPageSize(2);
        properties.getMetsaregister().setBulkPagesPerRun(1);
        properties.getMetsaregister().setMaxAttempts(1);
        properties.getMetsaregister().setMinRequestIntervalMs(0);
    }

    @Test
    void resumesPersistedStartIndexAfterRestartAndPublishesOnlyWhenComplete() {
        var firstService = service(uri -> {
            assertThat(uri.toString()).contains("startIndex=0", "count=2", "sortBy=sys_id%20A");
            return response(3, feature(1), feature(2));
        });

        var first = firstService.syncLayer(LAYER);

        assertThat(first.complete()).isFalse();
        assertThat(first.rowsStagedTotal()).isEqualTo(2);
        assertThat(currentGeneration()).isNull();
        assertThat(generationValue(first.generationId(), "next_start_index", Long.class)).isEqualTo(2L);

        var restartedService = service(uri -> {
            assertThat(uri.toString()).contains("startIndex=2", "count=2");
            return response(3, feature(3));
        });
        var second = restartedService.syncLayer(LAYER);

        assertThat(second.generationId()).isEqualTo(first.generationId());
        assertThat(second.complete()).isTrue();
        assertThat(second.rowsStagedTotal()).isEqualTo(3);
        assertThat(currentGeneration()).isEqualTo(first.generationId());
        assertThat(layerValue("state", String.class)).isEqualTo("HEALTHY");
        assertThat(layerValue("current_row_count", Long.class)).isEqualTo(3L);
    }

    @Test
    void providerCountMismatchKeepsLastKnownGoodGenerationCurrent() {
        properties.getMetsaregister().setPageSize(10);
        properties.getMetsaregister().setBulkPagesPerRun(10);
        AtomicInteger request = new AtomicInteger();
        var mirror = service(uri -> request.getAndIncrement() == 0
            ? response(1, feature(99))
            : response(2, feature(100)));

        var published = mirror.syncLayer(LAYER);
        UUID lastKnownGood = published.generationId();

        assertThatThrownBy(() -> mirror.syncLayer(LAYER))
            .isInstanceOf(ExternalRegistryService.RegistryException.class)
            .hasMessageContaining("provider reported 2");

        assertThat(currentGeneration()).isEqualTo(lastKnownGood);
        assertThat(layerValue("current_row_count", Long.class)).isEqualTo(1L);
        assertThat(layerValue("state", String.class)).isEqualTo("FAILED");
        assertThat(currentFeatureCount()).isEqualTo(1);
    }

    @Test
    void duplicateDeterministicFeatureKeyFailsClosedWithoutPublishingPartialGeneration() {
        properties.getMetsaregister().setPageSize(10);
        properties.getMetsaregister().setBulkPagesPerRun(10);
        var mirror = service(uri -> response(2, feature(7), feature(7)));

        assertThatThrownBy(() -> mirror.syncLayer(LAYER)).isInstanceOf(RuntimeException.class);

        assertThat(currentGeneration()).isNull();
        assertThat(layerValue("state", String.class)).isEqualTo("FAILED");
        assertThat(dsl.fetchCount(table("metsaregister_wfs_generation"))).isEqualTo(1);
    }

    @Test
    void publishingNewCompleteGenerationReflectsProviderDeletionsAndRetiresPreviousSnapshot() {
        properties.getMetsaregister().setPageSize(10);
        properties.getMetsaregister().setBulkPagesPerRun(10);
        AtomicInteger request = new AtomicInteger();
        var mirror = service(uri -> request.getAndIncrement() == 0
            ? response(2, feature(1), feature(2))
            : response(1, feature(2)));

        UUID first = mirror.syncLayer(LAYER).generationId();
        UUID second = mirror.syncLayer(LAYER).generationId();

        assertThat(second).isNotEqualTo(first);
        assertThat(currentGeneration()).isEqualTo(second);
        assertThat(currentFeatureCount()).isEqualTo(1);
        assertThat(dsl.fetchOne("""
            SELECT feature_key FROM metsaregister_wfs_feature
             WHERE generation_id=?
            """, second).get("feature_key", String.class)).isEqualTo("2");
        assertThat(generationValue(first, "state", String.class)).isEqualTo("RETIRED");
        assertThat(generationValue(second, "state", String.class)).isEqualTo("PUBLISHED");
    }

    private MetsaregisterWfsMirrorService service(MetsaregisterWfsClient.PageFetcher fetcher) {
        var client = new MetsaregisterWfsClient(properties, fetcher);
        var policy = new RegistryPayloadPolicy(json, properties);
        return new MetsaregisterWfsMirrorService(dsl, client, properties, policy, json, transactionManager);
    }

    private UUID currentGeneration() {
        return dsl.select(field(name("current_generation_id"), UUID.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(LAYER))
            .fetchOne(0, UUID.class);
    }

    private int currentFeatureCount() {
        UUID current = currentGeneration();
        if (current == null) return 0;
        return dsl.fetchCount(
            dsl.selectOne().from(table("metsaregister_wfs_feature"))
                .where(field(name("generation_id"), UUID.class).eq(current))
        );
    }

    private <T> T layerValue(String column, Class<T> type) {
        return dsl.select(field(name(column), type))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(LAYER))
            .fetchOne(0, type);
    }

    private <T> T generationValue(UUID id, String column, Class<T> type) {
        return dsl.select(field(name(column), type))
            .from(table("metsaregister_wfs_generation"))
            .where(field(name("id"), UUID.class).eq(id))
            .fetchOne(0, type);
    }

    private ObjectNode response(long matched, JsonNode... features) {
        ObjectNode response = json.createObjectNode();
        response.put("numberMatched", matched);
        response.put("numberReturned", features.length);
        var array = response.putArray("features");
        for (JsonNode feature : features) array.add(feature);
        return response;
    }

    private ObjectNode feature(long id) {
        ObjectNode feature = json.createObjectNode();
        feature.put("type", "Feature");
        feature.putObject("properties").put("sys_id", id).put("name", "feature-" + id);
        feature.putNull("geometry");
        return feature;
    }
}
