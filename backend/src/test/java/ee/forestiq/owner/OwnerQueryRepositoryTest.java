package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ee.forestiq.support.H2JooqTestDatabase;

class OwnerQueryRepositoryTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ASSIGNED_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CALLER_OWNER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID UNASSIGNED_OWNER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CROSS_ORG_OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private OwnerQueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("owners-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table owner (
                id uuid primary key,
                organization_id uuid not null,
                display_name varchar(300) not null,
                personal_or_registry_code varchar(32) not null,
                status varchar(32) not null,
                phone varchar(64),
                assigned_to_subject varchar(100),
                updated_at timestamp with time zone not null,
                status_set_at timestamp with time zone
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation (
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                active boolean not null
            )
            """);
        dsl.execute("""
            create table land_parcel (
                id uuid primary key,
                organization_id uuid not null
            )
            """);
        repository = new OwnerQueryRepository(dsl);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void callerSearchReturnsOnlyOwnersAssignedToCurrentSubject() {
        insertOwner(ASSIGNED_OWNER, "Assigned Owner", "caller-subject");
        insertOwner(OTHER_CALLER_OWNER, "Other Caller Owner", "other-subject");
        insertOwner(UNASSIGNED_OWNER, "Unassigned Owner", null);

        var owners = repository.findOwners(ORG_A, "", 0, 20, "caller-subject");

        assertThat(repository.countOwners(ORG_A, "", "caller-subject")).isEqualTo(1);
        assertThat(owners).extracting(OwnerDtos.OwnerListItem::id).containsExactly(ASSIGNED_OWNER);
    }

    @Test
    void unrestrictedSearchReturnsAllOrganizationOwners() {
        insertOwner(ASSIGNED_OWNER, "Assigned Owner", "caller-subject");
        insertOwner(OTHER_CALLER_OWNER, "Other Caller Owner", "other-subject");
        insertOwner(UNASSIGNED_OWNER, "Unassigned Owner", null);

        var owners = repository.findOwners(ORG_A, "", 0, 20, null);

        assertThat(repository.countOwners(ORG_A, "", null)).isEqualTo(3);
        assertThat(owners).extracting(OwnerDtos.OwnerListItem::id)
            .containsExactly(UNASSIGNED_OWNER, OTHER_CALLER_OWNER, ASSIGNED_OWNER);
    }

    @Test
    void searchAndCountCannotReadOwnersFromAnotherOrganization() {
        insertOwner(ORG_A, ASSIGNED_OWNER, "Visible Owner", "caller-subject");
        insertOwner(ORG_B, CROSS_ORG_OWNER, "Hidden Owner", "caller-subject");

        var owners = repository.findOwners(ORG_A, "", 0, 20, "caller-subject");

        assertThat(repository.countOwners(ORG_A, "", "caller-subject")).isEqualTo(1);
        assertThat(owners).extracting(OwnerDtos.OwnerListItem::id)
            .containsExactly(ASSIGNED_OWNER)
            .doesNotContain(CROSS_ORG_OWNER);
    }

    private void insertOwner(UUID ownerId, String name, String assignedTo) {
        insertOwner(ORG_A, ownerId, name, assignedTo);
    }

    private void insertOwner(UUID organizationId, UUID ownerId, String name, String assignedTo) {
        dsl.insertInto(table("owner"))
            .columns(field(name("id")), field(name("organization_id")), field(name("display_name")),
                field(name("personal_or_registry_code")), field(name("status")), field(name("phone")),
                field(name("assigned_to_subject")), field(name("updated_at")))
            .values(ownerId, organizationId, name, ownerId.toString().substring(0, 8), OwnerStatus.NEW, null,
                assignedTo, Instant.parse("2026-08-24T09:00:00Z").plusMillis(ownerId.getLeastSignificantBits() & 0xfff))
            .execute();
    }
}
