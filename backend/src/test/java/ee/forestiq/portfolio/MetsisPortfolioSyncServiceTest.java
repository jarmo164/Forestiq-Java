package ee.forestiq.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;

class MetsisPortfolioSyncServiceTest {
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ORG = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARCEL = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private MetsisPortfolioClient client;
    private OrganizationContext organizationContext;
    private MetsisPortfolioProperties properties;
    private MetsisPortfolioSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("metsis-portfolio-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table organization (
                id uuid primary key,
                name varchar(180) not null
            )
            """);
        dsl.execute("""
            create table metsis_portfolio_sync_checkpoint (
                organization_id uuid primary key,
                cursor_date date,
                last_success_at timestamp with time zone,
                last_failure_at timestamp with time zone,
                last_error varchar(1000),
                last_rows_seen integer,
                last_relations_added integer,
                last_relations_closed integer,
                updated_at timestamp with time zone
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation (
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                source varchar(80),
                active boolean not null,
                primary key (organization_id, owner_id, parcel_id)
            )
            """);
        dsl.execute("""
            create table owner_portfolio_import_stage (
                run_id uuid not null,
                organization_id uuid not null,
                source_row_number bigint not null,
                owner_code varchar(32) not null,
                owner_name varchar(300) not null,
                owner_type varchar(32) not null,
                cadastral_code varchar(32)
            )
            """);

        properties = new MetsisPortfolioProperties();
        properties.setUri("https://metsis.example.test/api/owners-test");
        properties.setAuthorization("test-token");
        properties.setOrganizationId(ORG.toString());
        properties.setPageSize(100);
        properties.setMaxPagesPerRun(2);

        client = mock(MetsisPortfolioClient.class);
        organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        service = new MetsisPortfolioSyncService(dsl, organizationContext, properties, client);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void configuredOrganizationUsesExplicitTenantId() {
        assertThat(service.configuredOrganizationId()).isEqualTo(ORG);
    }

    @Test
    void invalidConfiguredOrganizationIdIsRejected() {
        properties.setOrganizationId("not-a-uuid");

        assertThatThrownBy(service::configuredOrganizationId)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("UUID");
    }

    @Test
    void unconfiguredConnectionRejectsResolutionAndReturnsSkippedSync() {
        properties.setAuthorization(" ");

        assertThatThrownBy(service::configuredOrganizationId)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("ei ole seadistatud");

        var result = service.syncConfiguredOrganization("SCHEDULED").getFirst();
        assertThat(result.trigger()).isEqualTo("SCHEDULED");
        assertThat(result.failed()).isZero();
        assertThat(result.message()).contains("ei ole seadistatud");
    }

    @Test
    void configuredOrganizationCanBeInferredForSingleTenant() {
        properties.setOrganizationId("");
        dsl.insertInto(table("organization"))
            .columns(field("id"), field("name"))
            .values(ORG, "ForestIQ")
            .execute();

        assertThat(service.configuredOrganizationId()).isEqualTo(ORG);
    }

    @Test
    void configuredOrganizationInferenceRejectsMultipleTenants() {
        properties.setOrganizationId("");
        dsl.insertInto(table("organization"))
            .columns(field("id"), field("name"))
            .values(ORG, "ForestIQ")
            .execute();
        dsl.insertInto(table("organization"))
            .columns(field("id"), field("name"))
            .values(OTHER_ORG, "ForestIQ Other")
            .execute();

        assertThatThrownBy(service::configuredOrganizationId)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("täpselt üks organisatsioon");
    }

    @Test
    void statusIsEmptyBeforeFirstSync() {
        var status = service.statusForCurrentOrganization();

        assertThat(status.cursorDate()).isNull();
        assertThat(status.lastSuccessAt()).isNull();
        assertThat(status.lastFailureAt()).isNull();
        assertThat(status.lastRowsSeen()).isZero();
        assertThat(status.lastRelationsAdded()).isZero();
        assertThat(status.lastRelationsClosed()).isZero();
        assertThat(status.configured()).isTrue();
    }

    @Test
    void statusReturnsPersistedCheckpoint() {
        LocalDate cursor = LocalDate.of(2026, 8, 24);
        Instant success = Instant.parse("2026-08-24T08:00:00Z");
        Instant failure = Instant.parse("2026-08-24T09:00:00Z");
        dsl.insertInto(table("metsis_portfolio_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_date"), field("last_success_at"),
                field("last_failure_at"), field("last_error"), field("last_rows_seen"),
                field("last_relations_added"), field("last_relations_closed"), field("updated_at"))
            .values(ORG, cursor, success, failure, "temporary error", 12, 3, 1, failure)
            .execute();

        var status = service.statusForCurrentOrganization();

        assertThat(status.cursorDate()).isEqualTo(cursor);
        assertThat(status.lastSuccessAt()).isEqualTo(success);
        assertThat(status.lastFailureAt()).isEqualTo(failure);
        assertThat(status.lastError()).isEqualTo("temporary error");
        assertThat(status.lastRowsSeen()).isEqualTo(12);
        assertThat(status.lastRelationsAdded()).isEqualTo(3);
        assertThat(status.lastRelationsClosed()).isEqualTo(1);
        assertThat(status.configured()).isTrue();
    }

    @Test
    void configuredSyncConvertsProviderFailureToOperationalResult() {
        when(client.fetchPage(eq(0), eq(100))).thenThrow(new RuntimeException("provider\n unavailable"));

        var result = service.syncConfiguredOrganization("SCHEDULED").getFirst();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.message()).contains("provider  unavailable");
        assertThat(result.message()).doesNotContain("\n");
    }

    @Test
    void providerFailurePersistsFailureMetadataWithoutAdvancingSuccessCursor() {
        LocalDate previousCursor = LocalDate.of(2026, 8, 20);
        Instant previousSuccess = Instant.parse("2026-08-20T08:05:00Z");
        dsl.insertInto(table("metsis_portfolio_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_date"), field("last_success_at"), field("updated_at"))
            .values(ORG, previousCursor, previousSuccess, previousSuccess)
            .execute();
        when(client.fetchPage(eq(0), eq(100))).thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> service.syncOrganization(ORG, "SCHEDULED"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("provider unavailable");

        var checkpoint = dsl.select(
                field(name("cursor_date"), LocalDate.class),
                field(name("last_success_at"), Instant.class),
                field(name("last_failure_at"), Instant.class),
                field(name("last_error"), String.class))
            .from(table("metsis_portfolio_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(ORG))
            .fetchOne();

        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.value1()).isEqualTo(previousCursor);
        assertThat(checkpoint.value2()).isEqualTo(previousSuccess);
        assertThat(checkpoint.value3()).isNotNull();
        assertThat(checkpoint.value4()).contains("provider unavailable");
    }

    @Test
    void pageCapPreservesLastKnownGoodRelationAndDoesNotAdvanceCursor() {
        LocalDate previousCursor = LocalDate.of(2026, 8, 20);
        Instant previousSuccess = Instant.parse("2026-08-20T08:05:00Z");
        dsl.insertInto(table("metsis_portfolio_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_date"), field("last_success_at"), field("updated_at"))
            .values(ORG, previousCursor, previousSuccess, previousSuccess)
            .execute();
        dsl.insertInto(table("owner_parcel_relation"))
            .columns(field("organization_id"), field("owner_id"), field("parcel_id"), field("source"), field("active"))
            .values(ORG, OWNER, PARCEL, "METSIS_PORTFOLIO", true)
            .execute();

        var row = new MetsisPortfolioClient.PortfolioRow(
            "48901010011", "Existing Owner", "PERSON", List.of("12345:001:0001"));
        when(client.fetchPage(eq(0), eq(100)))
            .thenReturn(new MetsisPortfolioClient.PageResult(List.of(row), 1, true));
        when(client.fetchPage(eq(1), eq(100)))
            .thenReturn(new MetsisPortfolioClient.PageResult(List.of(row), 1, true));

        assertThatThrownBy(() -> service.syncOrganization(ORG, "SCHEDULED"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("lehekülgede piir");

        Boolean active = dsl.select(field(name("active"), Boolean.class))
            .from(table("owner_parcel_relation"))
            .where(field(name("organization_id"), UUID.class).eq(ORG)
                .and(field(name("owner_id"), UUID.class).eq(OWNER))
                .and(field(name("parcel_id"), UUID.class).eq(PARCEL)))
            .fetchOne(0, Boolean.class);
        assertThat(active).isTrue();

        assertThat(dsl.fetchCount(table("owner_portfolio_import_stage"))).isZero();

        var checkpoint = dsl.select(
                field(name("cursor_date"), LocalDate.class),
                field(name("last_success_at"), Instant.class),
                field(name("last_failure_at"), Instant.class))
            .from(table("metsis_portfolio_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(ORG))
            .fetchOne();
        assertThat(checkpoint.value1()).isEqualTo(previousCursor);
        assertThat(checkpoint.value2()).isEqualTo(previousSuccess);
        assertThat(checkpoint.value3()).isNotNull();
    }
}
