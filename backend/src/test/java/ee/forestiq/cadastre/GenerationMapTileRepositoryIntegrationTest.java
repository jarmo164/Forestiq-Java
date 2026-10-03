package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cache.type=simple",
        "spring.quartz.auto-startup=false",
        "forestiq.cors.allowed-origins=http://localhost:3000"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("core")
@Transactional
class GenerationMapTileRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_ID = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-3333-3333-3333-333333333333");
    private static final UUID GENERATION_ID = UUID.fromString("bbbbbbbb-4444-4444-4444-444444444444");
    private static final UUID OWNER_A_ID = UUID.fromString("bbbbbbbb-7777-7777-7777-777777777777");
    private static final UUID OWNER_B_ID = UUID.fromString("bbbbbbbb-8888-8888-8888-888888888888");
    private static final String CADASTRAL_CODE = "79501:002:0001";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockBean private JwtDecoder jwtDecoder;
    @Autowired private DSLContext dsl;
    @Autowired private GenerationMapTileRepository repository;

    @Test
    void publishedGenerationControlsTileAndSummaryGeometry() {
        insertOrganizationAndLegacyParcel();
        publishGeneration();

        assertThat(repository.currentCadastreGenerationId()).isEqualTo(GENERATION_ID);

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), GENERATION_ID, CADASTRAL_CODE)
            .orElseThrow();
        // Legacy domain geometry is near Tallinn; generation geometry is near Tartu.
        assertThat(summary.centroidLongitude()).isBetween(26.70, 26.74);
        assertThat(summary.centroidLatitude()).isBetween(58.36, 58.40);
        assertThat(summary.bboxWest()).isBetween(26.69, 26.72);
        assertThat(summary.generationId()).isEqualTo(GENERATION_ID);
        assertThat(summary.sourceDate()).isNotNull();
        assertThat(summary.ownerCount()).isZero();
        assertThat(summary.subPartCount()).isZero();
        assertThat(summary.activeNotificationCount()).isZero();
        assertThat(summary.notificationCount()).isZero();
        assertThat(summary.forestFeatureCount()).isZero();

        assertThat(repository.landParcelsOverviewTile(ORG_ID, Optional.empty(), GENERATION_ID, 10, 588, 306))
            .isNotEmpty();
        assertThat(repository.landParcelsTile(ORG_ID, Optional.empty(), GENERATION_ID, 12, 2352, 1225))
            .isNotEmpty();
    }

    @Test
    void summaryPreservesMultiOwnerSemanticsAndBoundedSectionCounts() {
        insertOrganizationAndLegacyParcel();
        publishGeneration();
        dsl.execute("""
            INSERT INTO owner
                (id, organization_id, display_name, personal_or_registry_code, phone, status, assigned_to_subject)
            VALUES
                (?, ?, 'Owner A', 'DETAIL-A', '+3725000001', 'NEW', 'caller-a'),
                (?, ?, 'Owner B', 'DETAIL-B', '+3725000002', 'CONTACTED', 'caller-b')
            """, OWNER_A_ID, ORG_ID, OWNER_B_ID, ORG_ID);
        dsl.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at)
            VALUES
                (?, ?, ?, 'METSIS_PORTFOLIO', true, CURRENT_TIMESTAMP),
                (?, ?, ?, 'FORESTIQ_LEGACY', true, CURRENT_TIMESTAMP)
            """, ORG_ID, OWNER_A_ID, PARCEL_ID, ORG_ID, OWNER_B_ID, PARCEL_ID);
        dsl.execute("""
            INSERT INTO cadastre_sub_part (organization_id, cadastral_code, sub_part_code, area)
            VALUES (?, ?, 1, 2.5), (?, ?, 2, 3.5)
            """, ORG_ID, CADASTRAL_CODE, ORG_ID, CADASTRAL_CODE);
        dsl.execute("""
            INSERT INTO cadastre_notification (organization_id, cadastral_code, notification_number, archived)
            VALUES (?, ?, 101, false), (?, ?, 102, true)
            """, ORG_ID, CADASTRAL_CODE, ORG_ID, CADASTRAL_CODE);
        dsl.execute("""
            INSERT INTO forest_registry_feature (organization_id, cadastral_code, source_layer, source_id)
            VALUES (?, ?, 'test-layer', 'feature-1')
            """, ORG_ID, CADASTRAL_CODE);

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), GENERATION_ID, CADASTRAL_CODE)
            .orElseThrow();

        assertThat(summary.ownerCount()).isEqualTo(2);
        assertThat(summary.owners()).extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
            .containsExactlyInAnyOrder(OWNER_A_ID, OWNER_B_ID);
        assertThat(summary.owners()).extracting(ParcelMapSummary.ParcelOwnerSummary::relationSource)
            .containsOnly("OWNER_PARCEL_RELATION");
        assertThat(summary.subPartCount()).isEqualTo(2);
        assertThat(summary.activeNotificationCount()).isEqualTo(1);
        assertThat(summary.notificationCount()).isEqualTo(2);
        assertThat(summary.forestFeatureCount()).isEqualTo(1);
        assertThat(summary.generationId()).isEqualTo(GENERATION_ID);
        assertThat(summary.sourceDate()).isNotNull();
    }

    @Test
    void summaryFallsBackToDomainGeometryWhenPublishedGenerationDoesNotContainParcel() {
        insertOrganizationAndLegacyParcel();
        publishGeneration("79501:002:9999");

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), GENERATION_ID, CADASTRAL_CODE)
            .orElseThrow();

        assertThat(summary.cadastralCode()).isEqualTo(CADASTRAL_CODE);
        assertThat(summary.centroidLongitude()).isBetween(24.70, 24.72);
        assertThat(summary.centroidLatitude()).isBetween(59.40, 59.42);
        assertThat(summary.bboxWest()).isBetween(24.69, 24.71);
        assertThat(summary.generationId()).isEqualTo(GENERATION_ID);
        assertThat(summary.sourceDate()).isNotNull();
        // Detail summary may fall back to the legacy domain boundary, but protected
        // generation-backed vector tiles must never mix geometry from outside the
        // explicitly requested published generation.
        assertThat(repository.landParcelsOverviewTile(ORG_ID, Optional.empty(), GENERATION_ID, 10, 582, 300))
            .isEmpty();
        assertThat(repository.landParcelsTile(ORG_ID, Optional.empty(), GENERATION_ID, 12, 2329, 1202))
            .isEmpty();
    }

    @Test
    void nonPublishedGenerationCannotLeakIntoCurrentPointer() {
        insertOrganizationAndLegacyParcel();
        publishGeneration();

        UUID secondRun = UUID.fromString("bbbbbbbb-5555-5555-5555-555555555555");
        UUID stagingGeneration = UUID.fromString("bbbbbbbb-6666-6666-6666-666666666666");
        dsl.execute("""
            INSERT INTO geospatial_source_run (id, source_key, trigger_type, status, started_at)
            VALUES (?, 'MARU_CADASTRE', 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP)
            """, secondRun);
        dsl.execute("""
            INSERT INTO geospatial_generation
                (id, source_key, run_id, status, canonical_srid)
            VALUES (?, 'MARU_CADASTRE', ?, 'STAGING', 3301)
            """, stagingGeneration, secondRun);

        assertThat(repository.currentCadastreGenerationId()).isEqualTo(GENERATION_ID);
        assertThat(repository.landParcelSummary(ORG_ID, Optional.empty(), stagingGeneration, CADASTRAL_CODE)).isEmpty();
    }

    private void insertOrganizationAndLegacyParcel() {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", ORG_ID, "Generation map test org");
        dsl.execute("""
            INSERT INTO land_parcel (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
            VALUES (?, ?, NULL, ?, 10.0,
                ST_GeomFromText('POLYGON((24.70 59.40,24.71 59.40,24.71 59.41,24.70 59.41,24.70 59.40))', 4326))
            """, PARCEL_ID, ORG_ID, CADASTRAL_CODE);
    }

    private void publishGeneration() {
        publishGeneration(CADASTRAL_CODE);
    }

    private void publishGeneration(String cadastralCode) {
        dsl.execute("""
            INSERT INTO geospatial_source_run
                (id, source_key, trigger_type, status, started_at)
            VALUES (?, 'MARU_CADASTRE', 'BOOTSTRAP', 'SUCCESS', CURRENT_TIMESTAMP)
            """, RUN_ID);
        dsl.execute("""
            INSERT INTO geospatial_generation
                (id, source_key, run_id, status, source_date, canonical_srid, validated_at, published_at)
            VALUES (?, 'MARU_CADASTRE', ?, 'STAGING', CURRENT_DATE, 3301, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, GENERATION_ID, RUN_ID);
        dsl.execute("""
            INSERT INTO geospatial_cadastre_parcel
                (source_key, generation_id, cadastral_code, source_area_m2, geom)
            VALUES ('MARU_CADASTRE', ?, ?, 100000,
                ST_Multi(ST_Transform(
                    ST_GeomFromText('POLYGON((26.70 58.37,26.74 58.37,26.74 58.39,26.70 58.39,26.70 58.37))', 4326),
                    3301)))
            """, GENERATION_ID, cadastralCode);
        dsl.execute("""
            UPDATE geospatial_generation
               SET status = 'PUBLISHED'
             WHERE id = ?
            """, GENERATION_ID);
        dsl.execute("""
            INSERT INTO geospatial_source_current (source_key, generation_id)
            VALUES ('MARU_CADASTRE', ?)
            """, GENERATION_ID);
    }
}
