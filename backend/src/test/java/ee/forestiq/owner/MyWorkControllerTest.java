package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

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
class MyWorkControllerTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_WITH_PARCELS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_WITHOUT_PARCELS = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_CALLER_OWNER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private OrganizationContext organizationContext;
    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private MyWorkController controller;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("my-work-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table owner (
                id uuid primary key, organization_id uuid not null, display_name varchar(300) not null,
                personal_or_registry_code varchar(32) not null, status varchar(32) not null, phone varchar(64),
                assigned_to_subject varchar(100), updated_at timestamp with time zone not null,
                status_set_at timestamp with time zone
            )
            """);
        dsl.execute("create table owner_parcel_relation (organization_id uuid not null, owner_id uuid not null, parcel_id uuid not null, active boolean not null)");
        dsl.execute("create table land_parcel (id uuid primary key, organization_id uuid not null)");
        controller = new MyWorkController(new MyWorkQueryRepository(dsl), organizationContext);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(organizationContext.subject()).thenReturn("caller-subject");
    }

    @AfterEach void tearDown() throws Exception { database.close(); }

    @Test
    void searchReturnsActiveParcelCountsForAssignedOwners() {
        insertOwner(OWNER_WITH_PARCELS, "Owner With Parcels", "caller-subject");
        insertOwner(OWNER_WITHOUT_PARCELS, "Owner Without Parcels", "caller-subject");
        insertOwner(OTHER_CALLER_OWNER, "Other Caller Owner", "other-subject");
        insertParcelRelation(OWNER_WITH_PARCELS, true);
        insertParcelRelation(OWNER_WITH_PARCELS, true);
        insertParcelRelation(OWNER_WITH_PARCELS, false);
        insertParcelRelation(OTHER_CALLER_OWNER, true);

        var response = controller.search("", 0, 20);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content()).extracting(OwnerDtos.OwnerListItem::id).containsExactly(OWNER_WITHOUT_PARCELS, OWNER_WITH_PARCELS);
        assertThat(response.content()).extracting(OwnerDtos.OwnerListItem::parcelCount).containsExactly(0L, 2L);
    }

    @Test
    void searchRejectsUnsafePaginationInputs() {
        insertOwner(OWNER_WITH_PARCELS, "Owner With Parcels", "caller-subject");
        assertThatThrownBy(() -> controller.search("", -3, 500))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void nextOwnerReturnsOldestEligibleAssignedOwner() {
        insertOwner(OWNER_WITH_PARCELS, "Owner With Parcels", "caller-subject", OwnerStatus.CONTACTED,
            Instant.parse("2026-08-24T10:00:00Z"));
        insertOwner(OWNER_WITHOUT_PARCELS, "Owner Without Parcels", "caller-subject", OwnerStatus.ASSIGNED,
            Instant.parse("2026-08-24T08:00:00Z"));
        insertOwner(OTHER_CALLER_OWNER, "Other Caller Owner", "other-subject", OwnerStatus.ASSIGNED,
            Instant.parse("2026-08-24T07:00:00Z"));
        var response = controller.nextOwner();
        assertThat(response).containsEntry("ownerId", OWNER_WITHOUT_PARCELS.toString());
    }

    private void insertOwner(UUID ownerId, String displayName, String assignedTo) {
        insertOwner(ownerId, displayName, assignedTo, OwnerStatus.ASSIGNED, ownerId.equals(OWNER_WITHOUT_PARCELS)
            ? Instant.parse("2026-08-24T08:00:00Z") : Instant.parse("2026-08-24T10:00:00Z"));
    }

    private void insertOwner(UUID ownerId, String displayName, String assignedTo, String status, Instant statusSetAt) {
        dsl.insertInto(table("owner"))
            .columns(field(name("id")), field(name("organization_id")), field(name("display_name")),
                field(name("personal_or_registry_code")), field(name("status")), field(name("assigned_to_subject")),
                field(name("updated_at")), field(name("status_set_at")))
            .values(ownerId, ORG_A, displayName, ownerId.toString().substring(0, 8), status, assignedTo,
                Instant.parse("2026-08-24T09:00:00Z"), statusSetAt).execute();
    }

    private void insertParcelRelation(UUID ownerId, boolean active) {
        UUID parcelId = UUID.randomUUID();
        dsl.insertInto(table("land_parcel")).columns(field(name("id")), field(name("organization_id"))).values(parcelId, ORG_A).execute();
        dsl.insertInto(table("owner_parcel_relation"))
            .columns(field(name("organization_id")), field(name("owner_id")), field(name("parcel_id")), field(name("active")))
            .values(ORG_A, ownerId, parcelId, active).execute();
    }
}
