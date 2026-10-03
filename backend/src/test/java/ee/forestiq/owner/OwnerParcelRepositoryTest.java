package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ee.forestiq.support.H2JooqTestDatabase;

class OwnerParcelRepositoryTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARCEL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String CADASTRAL_CODE = "12345:001:0001";

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private OwnerParcelRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("owner-parcel-repository-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table land_parcel (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid,
                cadastral_code varchar(100) not null,
                marked boolean not null default false
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation (
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                active boolean not null,
                primary key (organization_id, owner_id, parcel_id)
            )
            """);
        repository = new OwnerParcelRepository(dsl);

        dsl.insertInto(table("land_parcel"))
            .columns(field("id"), field("organization_id"), field("owner_id"), field("cadastral_code"), field("marked"))
            .values(PARCEL, ORG, OWNER_A, CADASTRAL_CODE, false)
            .execute();
        insertRelation(OWNER_A, true);
        insertRelation(OWNER_B, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void coOwnerCanMarkParcelEvenWhenDeprecatedProjectionPointsToAnotherOwner() {
        repository.markParcels(ORG, OWNER_B, List.of(CADASTRAL_CODE));

        assertThat(marked()).isTrue();
    }

    @Test
    void coOwnerCanUnmarkParcelEvenWhenDeprecatedProjectionPointsToAnotherOwner() {
        dsl.update(table("land_parcel"))
            .set(field(name("marked"), Boolean.class), true)
            .where(field(name("id"), UUID.class).eq(PARCEL))
            .execute();

        repository.unmarkAllParcels(ORG, OWNER_B);

        assertThat(marked()).isFalse();
    }

    @Test
    void inactiveRelationCannotControlParcelMarking() {
        dsl.update(table("owner_parcel_relation"))
            .set(field(name("active"), Boolean.class), false)
            .where(field(name("organization_id"), UUID.class).eq(ORG)
                .and(field(name("owner_id"), UUID.class).eq(OWNER_B)))
            .execute();

        repository.markParcels(ORG, OWNER_B, List.of(CADASTRAL_CODE));

        assertThat(marked()).isFalse();
    }

    @Test
    void missingBoundaryDoesNotBecomeNullIslandCentroid() {
        assertThat(OwnerParcelRepository.coordinates(null, null)).isNull();
        assertThat(OwnerParcelRepository.coordinates(0.0, 0.0)).isNotNull();
    }

    private void insertRelation(UUID ownerId, boolean active) {
        dsl.insertInto(table("owner_parcel_relation"))
            .columns(field("organization_id"), field("owner_id"), field("parcel_id"), field("active"))
            .values(ORG, ownerId, PARCEL, active)
            .execute();
    }

    private Boolean marked() {
        return dsl.select(field(name("marked"), Boolean.class))
            .from(table("land_parcel"))
            .where(field(name("id"), UUID.class).eq(PARCEL))
            .fetchOne(0, Boolean.class);
    }
}
