package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MapTileRepositoryIsolationTest {
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID OTHER_ORG_ID = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID OWNER_A = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222");
    private static final UUID OWNER_B = UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333");
    private static final UUID OTHER_ORG_OWNER = UUID.fromString("bbbbbbbb-2222-2222-2222-bbbbbbbbbbbb");
    private static final UUID PARCEL_ID = UUID.fromString("aaaaaaaa-4444-4444-4444-444444444444");
    private static final UUID OTHER_ORG_PARCEL_ID = UUID.fromString("bbbbbbbb-4444-4444-4444-444444444444");
    private static final String CADASTRAL_CODE = "99999:999:9999";
    private static final String OTHER_ORG_CADASTRAL_CODE = "88888:888:8888";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
    }

    @Test
    void callerScopedParcelSummaryDoesNotExposeOtherAssignedOwners() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            seed(dsl);
            MapTileRepository repository = new MapTileRepository(dsl);

            var callerSummary = repository.landParcelSummary(ORG_ID, Optional.of("caller-a"), CADASTRAL_CODE);
            assertThat(callerSummary).isPresent();
            assertThat(callerSummary.orElseThrow().ownerId()).isEqualTo(OWNER_A);
            assertThat(callerSummary.orElseThrow().owners())
                .extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
                .containsExactly(OWNER_A);

            var managerSummary = repository.landParcelSummary(ORG_ID, Optional.empty(), CADASTRAL_CODE);
            assertThat(managerSummary).isPresent();
            assertThat(managerSummary.orElseThrow().owners())
                .extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
                .containsExactly(OWNER_A, OWNER_B);
        }
    }

    @Test
    void parcelSummaryCannotReadParcelFromAnotherOrganization() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            seed(dsl);
            MapTileRepository repository = new MapTileRepository(dsl);

            assertThat(repository.landParcelSummary(ORG_ID, Optional.empty(), OTHER_ORG_CADASTRAL_CODE)).isEmpty();
            assertThat(repository.landParcelSummary(OTHER_ORG_ID, Optional.empty(), OTHER_ORG_CADASTRAL_CODE)).isPresent();
        }
    }

    @Test
    void vectorTileCountsOnlyFeaturesFromRequestedOrganization() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            seed(dsl);
            MapTileRepository repository = new MapTileRepository(dsl, meterRegistry);

            byte[] tile = repository.landParcelsTile(ORG_ID, Optional.empty(), 8, 145, 75);

            assertThat(tile).isNotEmpty();
            assertThat(meterRegistry.get("forestiq.map.tile.features")
                .tag("layer", "land_parcel")
                .tag("zoom", "8")
                .summary()
                .totalAmount()).isEqualTo(1.0d);
        } finally {
            meterRegistry.clear();
        }
    }

    private static void seed(DSLContext dsl) {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING", ORG_ID, "Isolation test org");
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING", OTHER_ORG_ID, "Other isolation test org");
        dsl.execute("""
            INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, status, assigned_to_subject)
            VALUES (?, ?, 'Caller A owner', 'iso-a', 'NEW', 'caller-a'),
                   (?, ?, 'Caller B owner', 'iso-b', 'NEW', 'caller-b'),
                   (?, ?, 'Other org owner', 'iso-other', 'NEW', 'caller-a')
            ON CONFLICT DO NOTHING
            """, OWNER_A, ORG_ID, OWNER_B, ORG_ID, OTHER_ORG_OWNER, OTHER_ORG_ID);
        dsl.execute("""
            INSERT INTO land_parcel (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
            VALUES (?, ?, ?, ?, 10.0, ST_GeomFromText('POLYGON((24 59,24.01 59,24.01 59.01,24 59.01,24 59))', 4326)),
                   (?, ?, ?, ?, 11.0, ST_GeomFromText('POLYGON((24.02 59,24.03 59,24.03 59.01,24.02 59.01,24.02 59))', 4326))
            ON CONFLICT DO NOTHING
            """, PARCEL_ID, ORG_ID, OWNER_A, CADASTRAL_CODE,
            OTHER_ORG_PARCEL_ID, OTHER_ORG_ID, OTHER_ORG_OWNER, OTHER_ORG_CADASTRAL_CODE);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, 'TEST', true, ?::timestamptz, ?::timestamptz, ?::timestamptz),
                   (?, ?, ?, 'TEST', true, ?::timestamptz, ?::timestamptz, ?::timestamptz),
                   (?, ?, ?, 'TEST', true, ?::timestamptz, ?::timestamptz, ?::timestamptz)
            ON CONFLICT DO NOTHING
            """,
            ORG_ID, OWNER_A, PARCEL_ID, now, now.minusSeconds(1), now,
            ORG_ID, OWNER_B, PARCEL_ID, now, now, now,
            OTHER_ORG_ID, OTHER_ORG_OWNER, OTHER_ORG_PARCEL_ID, now, now, now);
    }
}
