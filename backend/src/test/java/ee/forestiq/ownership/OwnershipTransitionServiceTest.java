package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;

@ExtendWith(MockitoExtension.class)
class OwnershipTransitionServiceTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARCEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private OrganizationContext organizationContext;
    @Mock private WeaselOwnershipClient client;

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private WeaselProperties properties;
    private OwnershipTransitionService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("ownership-transition-" + UUID.randomUUID());
        dsl = database.dsl();
        createSchema();
        properties = configuredProperties(ORG_A);
        service = new OwnershipTransitionService(dsl, new ObjectMapper(), organizationContext, properties, client);
    }

    @AfterEach
    void tearDown() throws Exception { database.close(); }

    @Test
    void syncImportsMatchingTransitionAsEventSignalAndActivity() {
        insertOwnerAndParcel("48901010011");
        var transition = new WeaselOwnershipClient.OwnershipTransition(
            12345L, "12345:001:0001", Instant.parse("2026-08-20T10:00:00Z"),
            List.of("48901010011"), List.of("39001010022"));
        when(client.fetchTransitions(any(Instant.class), any(Instant.class), eq(100), eq(1)))
            .thenReturn(new WeaselOwnershipClient.TransitionPage(1, List.of(transition)));

        var result = service.syncOrganization(ORG_A, "MANUAL");

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(dsl.fetchCount(table("ownership_transition_event"))).isEqualTo(1);
        assertThat(dsl.fetchCount(table("data_change_signal"))).isEqualTo(1);
        assertThat(dsl.fetchCount(table("owner_activity"))).isEqualTo(1);
        assertThat(dsl.select(field(name("relationship_direction"), String.class)).from(table("ownership_transition_event")).fetchOne(0, String.class))
            .isEqualTo("OUTGOING");
        assertThat(dsl.fetchExists(table("weasel_transition_sync_checkpoint"), field(name("organization_id"), UUID.class).eq(ORG_A))).isTrue();
    }

    @Test
    void providerFailurePersistsFailureMetadataWithoutAdvancingSuccessCursor() {
        Instant previousCursor = Instant.parse("2026-08-20T08:00:00Z");
        Instant previousSuccess = Instant.parse("2026-08-20T08:05:00Z");
        dsl.insertInto(table("weasel_transition_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_time"), field("last_success_at"), field("updated_at"))
            .values(ORG_A, previousCursor, previousSuccess, previousSuccess).execute();
        when(client.fetchTransitions(any(Instant.class), any(Instant.class), eq(100), eq(1)))
            .thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> service.syncOrganization(ORG_A, "SCHEDULED"))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("provider unavailable");

        var checkpoint = dsl.select(field("cursor_time", Instant.class), field("last_success_at", Instant.class),
                field("last_failure_at", Instant.class), field("last_error", String.class))
            .from(table("weasel_transition_sync_checkpoint"))
            .where(field("organization_id", UUID.class).eq(ORG_A)).fetchOne();
        assertThat(checkpoint.value1()).isEqualTo(previousCursor);
        assertThat(checkpoint.value2()).isEqualTo(previousSuccess);
        assertThat(checkpoint.value3()).isNotNull();
        assertThat(checkpoint.value4()).contains("provider unavailable");
    }

    @Test
    void pageCapPersistsResumeCursorWithoutAdvancingSuccessfulWindow() {
        Instant previousCursor = Instant.parse("2026-08-20T08:00:00Z");
        dsl.insertInto(table("weasel_transition_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_time"), field("updated_at"))
            .values(ORG_A, previousCursor, previousCursor).execute();
        when(client.fetchTransitions(any(Instant.class), any(Instant.class), eq(100), eq(1)))
            .thenReturn(new WeaselOwnershipClient.TransitionPage(101, List.of()));

        var result = service.syncOrganization(ORG_A, "SCHEDULED");

        assertThat(result.message()).contains("jätkatakse leheküljelt 2");
        var checkpoint = dsl.select(field("cursor_time", Instant.class), field("window_end", Instant.class),
                field("next_page", Integer.class))
            .from(table("weasel_transition_sync_checkpoint"))
            .where(field("organization_id", UUID.class).eq(ORG_A)).fetchOne();
        assertThat(checkpoint.value1()).isEqualTo(previousCursor);
        assertThat(checkpoint.value2()).isNotNull();
        assertThat(checkpoint.value3()).isEqualTo(2);
    }

    @Test
    void syncIgnoresTransitionsUnrelatedToKnownOwnerIdentifier() {
        insertOwnerAndParcel("48901010011");
        var transition = new WeaselOwnershipClient.OwnershipTransition(
            12346L, "12345:001:0001", Instant.parse("2026-08-20T10:00:00Z"),
            List.of("38001010033"), List.of("39001010022"));
        when(client.fetchTransitions(any(Instant.class), any(Instant.class), eq(100), eq(1)))
            .thenReturn(new WeaselOwnershipClient.TransitionPage(1, List.of(transition)));

        var result = service.syncOrganization(ORG_A, "MANUAL");

        assertThat(result.matched()).isZero();
        assertThat(result.imported()).isZero();
        assertThat(dsl.fetchCount(table("ownership_transition_event"))).isZero();
        assertThat(dsl.fetchCount(table("data_change_signal"))).isZero();
    }

    @Test
    void syncRejectsOrganizationDifferentFromConfiguredWeaselTenant() {
        properties.setOrganizationId(ORG_B.toString());
        assertThatThrownBy(() -> service.syncOrganization(ORG_A, "MANUAL"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ei ole seotud");
        verify(client, never()).fetchTransitions(any(Instant.class), any(Instant.class), any(Integer.class), any(Integer.class));
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("create table owner (id uuid primary key, organization_id uuid not null, personal_or_registry_code varchar(32))");
        dsl.execute("create table land_parcel (id uuid primary key, organization_id uuid not null, owner_id uuid, cadastral_code varchar(32) not null)");
        dsl.execute("create table owner_parcel_relation (organization_id uuid not null, owner_id uuid not null, parcel_id uuid not null, active boolean not null)");
        dsl.execute("""
            create table ownership_transition_event (
                organization_id uuid not null, parcel_id uuid not null, owner_id uuid not null,
                weasel_transition_id bigint not null, cadastral_code varchar(32), transition_at timestamp with time zone,
                old_owner_count smallint, new_owner_count smallint, relationship_direction varchar(32),
                imported_by_subject varchar(100), imported_at timestamp with time zone default current_timestamp,
                unique (organization_id, weasel_transition_id, owner_id)
            )
            """);
        dsl.execute("""
            create table data_change_signal (
                id uuid primary key, organization_id uuid not null, owner_id uuid not null, parcel_id uuid,
                signal_type varchar(48), source_registry varchar(80), source_key varchar(180), summary varchar(1000),
                payload jsonb, created_by_subject varchar(100), unique (organization_id, source_registry, source_key, owner_id)
            )
            """);
        dsl.execute("create table owner_activity (organization_id uuid not null, owner_id uuid not null, activity_type varchar(32), content varchar(1000), created_by_subject varchar(100))");
        dsl.execute("""
            create table weasel_transition_sync_checkpoint (
                organization_id uuid primary key,
                cursor_time timestamp with time zone,
                window_end timestamp with time zone,
                next_page integer,
                last_success_at timestamp with time zone,
                last_failure_at timestamp with time zone,
                last_error varchar(1000),
                updated_at timestamp with time zone
            )
            """);
    }

    private void insertOwnerAndParcel(String ownerCode) {
        dsl.insertInto(table("owner"))
            .columns(field("id"), field("organization_id"), field("personal_or_registry_code"))
            .values(OWNER_ID, ORG_A, ownerCode).execute();
        dsl.insertInto(table("land_parcel"))
            .columns(field("id"), field("organization_id"), field("owner_id"), field("cadastral_code"))
            .values(PARCEL_ID, ORG_A, OWNER_ID, "12345:001:0001").execute();
        dsl.insertInto(table("owner_parcel_relation"))
            .columns(field("organization_id"), field("owner_id"), field("parcel_id"), field("active"))
            .values(ORG_A, OWNER_ID, PARCEL_ID, true).execute();
    }

    private static WeaselProperties configuredProperties(UUID orgId) {
        WeaselProperties properties = new WeaselProperties();
        properties.setBaseUrl("https://weasel.example.test");
        properties.setAccessToken("test-token");
        properties.setOrganizationId(orgId.toString());
        properties.setPageSize(100);
        properties.setMaxPagesPerRun(1);
        properties.setInitialLookbackDays(14);
        properties.setOverlapHours(6);
        return properties;
    }
}
