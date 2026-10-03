package ee.forestiq.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ee.forestiq.security.OrganizationContext;

@Testcontainers(disabledWithoutDocker = true)
class MetsisPortfolioManualReconciliationTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARCEL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String OWNER_CODE = "48901010011";
    private static final String CADASTRAL_CODE = "12345:001:0001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("forestiq_test")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;
    private MetsisPortfolioClient client;
    private MetsisPortfolioSyncService service;

    @BeforeEach
    void setUp() {
        dsl = DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dsl.configuration().set(SQLDialect.POSTGRES);
        resetSchema();

        MetsisPortfolioProperties properties = new MetsisPortfolioProperties();
        properties.setUri("https://metsis.example.test/api/owners-test");
        properties.setAuthorization("test-token");
        properties.setOrganizationId(ORG.toString());
        properties.setPageSize(100);
        properties.setMaxPagesPerRun(10);

        client = mock(MetsisPortfolioClient.class);
        OrganizationContext organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        service = new MetsisPortfolioSyncService(dsl, organizationContext, properties, client);

        dsl.execute("""
            insert into owner
                (id, organization_id, display_name, personal_or_registry_code, owner_type,
                 source_display_name, source_owner_type, status, updated_at)
            values (?, ?, 'Manual Owner', ?, 'ERAISIK', 'Manual Owner', 'ERAISIK', 'NEW', current_timestamp)
            """, OWNER, ORG, OWNER_CODE);
        dsl.execute("""
            insert into land_parcel
                (id, organization_id, cadastral_code, area_hectares, name, updated_at)
            values (?, ?, ?, 10.0, 'Test parcel', current_timestamp)
            """, PARCEL, ORG, CADASTRAL_CODE);
    }

    @Test
    void providerActiveObservationCannotReactivateProtectedManualEndAndConflictIsDeduplicated() {
        insertProtectedRelation(false, 1);
        var row = new MetsisPortfolioClient.PortfolioRow(
            OWNER_CODE, "Manual Owner", "PERSON", List.of(CADASTRAL_CODE));
        when(client.fetchPage(eq(0), eq(100)))
            .thenReturn(new MetsisPortfolioClient.PageResult(List.of(row), 1, false));

        service.syncOrganization(ORG, "SCHEDULED");
        service.syncOrganization(ORG, "SCHEDULED");

        assertThat(relationActive()).isFalse();
        assertThat(openConflictCount(true)).isEqualTo(1);
        assertThat(relationManualOverride()).isTrue();
    }

    @Test
    void providerAbsenceCannotCloseProtectedManualActiveRelation() {
        insertProtectedRelation(true, 3);
        when(client.fetchPage(eq(0), eq(100)))
            .thenReturn(new MetsisPortfolioClient.PageResult(List.of(), 0, false));

        service.syncOrganization(ORG, "SCHEDULED");

        assertThat(relationActive()).isTrue();
        assertThat(openConflictCount(false)).isEqualTo(1);
        assertThat(relationManualOverride()).isTrue();
    }

    @Test
    void ownerWithoutCadastresSurvivesCopyStagingWithoutInventedRelation() {
        var row = new MetsisPortfolioClient.PortfolioRow(
            "39001010001", "Owner Without Parcels", "PERSON", List.of());
        when(client.fetchPage(eq(0), eq(100)))
            .thenReturn(new MetsisPortfolioClient.PageResult(List.of(row), 1, false));

        service.syncOrganization(ORG, "SCHEDULED");

        Integer owners = dsl.fetchOne("select count(*)::int c from owner where personal_or_registry_code='39001010001'")
            .get("c", Integer.class);
        Integer relations = dsl.fetchOne("""
            select count(*)::int c
              from owner_parcel_relation r
              join owner o on o.organization_id=r.organization_id and o.id=r.owner_id
             where o.personal_or_registry_code='39001010001'
            """).get("c", Integer.class);
        assertThat(owners).isEqualTo(1);
        assertThat(relations).isZero();
    }

    private void insertProtectedRelation(boolean active, int revision) {
        dsl.execute("""
            insert into owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at,
                 deactivated_at, manual_override, manual_revision, updated_at)
            values (?, ?, ?, 'MANUAL', ?, current_timestamp, cast(? as timestamptz), true, ?, current_timestamp)
            """, ORG, OWNER, PARCEL, active, active ? null : java.time.Instant.now(), revision);
    }

    private Boolean relationActive() {
        return dsl.select(field(name("active"), Boolean.class))
            .from(table("owner_parcel_relation"))
            .where(field(name("organization_id"), UUID.class).eq(ORG)
                .and(field(name("owner_id"), UUID.class).eq(OWNER))
                .and(field(name("parcel_id"), UUID.class).eq(PARCEL)))
            .fetchOne(0, Boolean.class);
    }

    private Boolean relationManualOverride() {
        return dsl.select(field(name("manual_override"), Boolean.class))
            .from(table("owner_parcel_relation"))
            .where(field(name("organization_id"), UUID.class).eq(ORG)
                .and(field(name("owner_id"), UUID.class).eq(OWNER))
                .and(field(name("parcel_id"), UUID.class).eq(PARCEL)))
            .fetchOne(0, Boolean.class);
    }

    private int openConflictCount(boolean observedActive) {
        return dsl.fetchCount(
            dsl.selectOne().from(table("ownership_relation_conflict"))
                .where(field(name("organization_id"), UUID.class).eq(ORG)
                    .and(field(name("owner_id"), UUID.class).eq(OWNER))
                    .and(field(name("parcel_id"), UUID.class).eq(PARCEL))
                    .and(field(name("status"), String.class).eq("OPEN"))
                    .and(field(name("observed_active"), Boolean.class).eq(observedActive)))
        );
    }

    private void resetSchema() {
        dsl.execute("drop table if exists owner_portfolio_import_stage cascade");
        dsl.execute("drop table if exists ownership_relation_conflict cascade");
        dsl.execute("drop table if exists owner_parcel_snapshot_row cascade");
        dsl.execute("drop table if exists owner_snapshot_row cascade");
        dsl.execute("drop table if exists data_change_signal cascade");
        dsl.execute("drop table if exists owner_parcel_relation cascade");
        dsl.execute("drop table if exists land_parcel cascade");
        dsl.execute("drop table if exists owner cascade");
        dsl.execute("drop table if exists metsis_portfolio_sync_checkpoint cascade");

        dsl.execute("""
            create table metsis_portfolio_sync_checkpoint (
                organization_id uuid primary key,
                cursor_date date,
                last_success_at timestamptz,
                last_failure_at timestamptz,
                last_error varchar(1000),
                last_rows_seen integer,
                last_relations_added integer,
                last_relations_closed integer,
                updated_at timestamptz
            )
            """);
        dsl.execute("""
            create table owner (
                id uuid primary key,
                organization_id uuid not null,
                display_name varchar(300) not null,
                personal_or_registry_code varchar(64) not null,
                owner_type varchar(32) not null,
                source_display_name varchar(300),
                source_owner_type varchar(32),
                status varchar(32) not null,
                updated_at timestamptz not null,
                unique (organization_id, personal_or_registry_code)
            )
            """);
        dsl.execute("""
            create table land_parcel (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid,
                cadastral_code varchar(100) not null,
                area_hectares numeric(14,4) not null,
                name varchar(300),
                updated_at timestamptz not null,
                unique (organization_id, cadastral_code)
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation (
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                source varchar(80) not null,
                active boolean not null,
                source_seen_at timestamptz,
                deactivated_at timestamptz,
                manual_override boolean not null default false,
                manual_revision integer not null default 0,
                updated_at timestamptz not null default current_timestamp,
                primary key (organization_id, owner_id, parcel_id)
            )
            """);
        dsl.execute("""
            create unlogged table owner_portfolio_import_stage (
                run_id uuid not null,
                organization_id uuid not null,
                source_row_number bigint not null,
                owner_code varchar(64) not null,
                owner_name varchar(300) not null,
                owner_type varchar(32) not null,
                cadastral_code varchar(100)
            )
            """);
        dsl.execute("""
            create unlogged table owner_snapshot_row (
                run_id uuid not null,
                organization_id uuid not null,
                owner_code varchar(64) not null,
                owner_name varchar(300) not null,
                owner_type varchar(32),
                primary key (run_id, organization_id, owner_code)
            )
            """);
        dsl.execute("""
            create unlogged table owner_parcel_snapshot_row (
                run_id uuid not null,
                organization_id uuid not null,
                owner_code varchar(64) not null,
                owner_name varchar(300) not null,
                owner_type varchar(32),
                cadastral_code varchar(100) not null,
                unique (run_id, organization_id, owner_code, cadastral_code)
            )
            """);
        dsl.execute("""
            create table ownership_relation_conflict (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                source varchar(32) not null,
                observed_active boolean not null,
                relation_revision integer not null,
                status varchar(16) not null,
                observed_at timestamptz not null,
                created_at timestamptz not null default current_timestamp,
                resolved_at timestamptz,
                resolved_by_subject varchar(255),
                resolution varchar(32),
                resolution_reason text,
                unique (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
            )
            """);
        dsl.execute("""
            create table data_change_signal (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid,
                parcel_id uuid,
                signal_type varchar(80) not null,
                source_registry varchar(80) not null,
                source_key varchar(255) not null,
                summary text not null,
                payload jsonb not null,
                created_by_subject varchar(255) not null,
                unique (organization_id, signal_type, source_registry, source_key)
            )
            """);
    }
}
