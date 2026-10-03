package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import ee.forestiq.support.H2JooqTestDatabase;

class RegistryDataRepositoryTest {

    @Test
    void maaametAttributeRefreshUsesDatabaseTimestampForParcelUpdate() throws Exception {
        try (H2JooqTestDatabase database = H2JooqTestDatabase.create("registry-maaamet-update-" + UUID.randomUUID())) {
            DSLContext dsl = database.dsl();
            createTables(dsl);
            UUID orgId = UUID.randomUUID();
            insertParcel(dsl, orgId, "12345:001:0001", Instant.now().minus(30, ChronoUnit.DAYS));

            RegistryDataRepository repository = repository(dsl);
            int changed = repository.updateMaaametAttributes(orgId, "12345:001:0001",
                new ExternalRegistryService.MaaametCadastre(
                    "12345:001:0001",
                    "Test address",
                    "Harju maakond",
                    "Tallinn",
                    "123",
                    "Eraomand",
                    BigDecimal.ONE,
                    BigDecimal.valueOf(0.5),
                    BigDecimal.valueOf(0.2),
                    BigDecimal.valueOf(0.1),
                    BigDecimal.valueOf(0.2),
                    null,
                    null));

            assertThat(changed).isEqualTo(1);
            assertThat(dsl.select(field(name("updated_at"), Instant.class))
                .from(table("land_parcel"))
                .where(field(name("organization_id")).eq(orgId))
                .fetchOne(field(name("updated_at"), Instant.class))).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
        }
    }

    @Test
    void recentlyFailedParcelDoesNotStarveNeverAttemptedBacklog() throws Exception {
        try (H2JooqTestDatabase database = H2JooqTestDatabase.create("registry-queue-" + UUID.randomUUID())) {
            DSLContext dsl = database.dsl();
            createTables(dsl);
            UUID orgId = UUID.randomUUID();
            Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
            insertParcel(dsl, orgId, "A", old);
            insertParcel(dsl, orgId, "B", old);
            insertLog(dsl, orgId, "A", "FAILED", Instant.now());

            RegistryDataRepository repository = repository(dsl);
            var batch = repository.staleParcelsForSource(orgId, "MAAAMET_WFS",
                Instant.now().minus(7, ChronoUnit.DAYS), 1);

            assertThat(batch).singleElement().extracting(RegistryDataRepository.ParcelRef::cadastralCode)
                .isEqualTo("B");
        }
    }

    @Test
    void morePoisonRowsThanOneBatchCannotStarveHealthyBacklogAndAreRetriedLater() throws Exception {
        try (H2JooqTestDatabase database = H2JooqTestDatabase.create("registry-poison-queue-" + UUID.randomUUID())) {
            DSLContext dsl = database.dsl();
            createTables(dsl);
            UUID orgId = UUID.randomUUID();
            Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            Instant recentFailure = Instant.now().minus(1, ChronoUnit.MINUTES);

            for (String code : List.of("F1", "F2", "F3", "N1", "N2")) insertParcel(dsl, orgId, code, old);
            for (String code : List.of("F1", "F2", "F3")) insertLog(dsl, orgId, code, "FAILED", recentFailure);

            RegistryDataRepository repository = repository(dsl);

            var firstBatch = repository.staleParcelsForSource(orgId, "MAAAMET_WFS", cutoff, 2);
            assertThat(firstBatch).extracting(RegistryDataRepository.ParcelRef::cadastralCode)
                .containsExactly("N1", "N2");

            // Simulate successful processing of the never-attempted rows. The poison rows
            // must remain eligible and rotate back into later cycles instead of disappearing.
            insertLog(dsl, orgId, "N1", "SUCCESS", Instant.now());
            insertLog(dsl, orgId, "N2", "SUCCESS", Instant.now());

            var secondBatch = repository.staleParcelsForSource(orgId, "MAAAMET_WFS", cutoff, 2);
            assertThat(secondBatch).extracting(RegistryDataRepository.ParcelRef::cadastralCode)
                .containsExactly("F1", "F2");
        }
    }

    private static RegistryDataRepository repository(DSLContext dsl) {
        return new RegistryDataRepository(dsl, new RegistryPayloadPolicy(new ObjectMapper(), new RegistryProperties()));
    }

    private static void createTables(DSLContext dsl) {
        dsl.execute("""
            create table land_parcel (
                id uuid primary key,
                organization_id uuid not null,
                cadastral_code varchar(64) not null,
                name varchar(255),
                address varchar(255),
                county varchar(120),
                municipality varchar(120),
                reg_nr varchar(64),
                parcel_type varchar(64),
                area_hectares numeric(12, 4),
                forest_area numeric(12, 4),
                arable_area numeric(12, 4),
                yard_area numeric(12, 4),
                other_area numeric(12, 4),
                updated_at timestamp with time zone
            )
            """);
        dsl.execute("""
            create table registry_sync_log (
                organization_id uuid not null,
                cadastral_code varchar(64) not null,
                registry_name varchar(80) not null,
                status varchar(20) not null,
                created_at timestamp with time zone not null
            )
            """);
    }

    private static void insertParcel(DSLContext dsl, UUID orgId, String code, Instant updatedAt) {
        dsl.insertInto(table("land_parcel"))
            .columns(field(name("id")), field(name("organization_id")), field(name("cadastral_code")), field(name("updated_at")))
            .values(UUID.randomUUID(), orgId, code, updatedAt)
            .execute();
    }

    private static void insertLog(DSLContext dsl, UUID orgId, String code, String status, Instant createdAt) {
        dsl.insertInto(table("registry_sync_log"))
            .columns(field(name("organization_id")), field(name("cadastral_code")), field(name("registry_name")),
                field(name("status")), field(name("created_at")))
            .values(orgId, code, "MAAAMET_WFS", status, createdAt)
            .execute();
    }
}
