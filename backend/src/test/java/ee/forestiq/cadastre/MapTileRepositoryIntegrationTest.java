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
class MapTileRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222");
    private static final UUID OWNER_A_ID = UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333");
    private static final UUID OWNER_B_ID = UUID.fromString("aaaaaaaa-4444-4444-4444-444444444444");
    private static final String CADASTRAL_CODE = "99999:999:9999";

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
    @Autowired private MapTileRepository repository;

    @Test
    void organizationWideMapIncludesParcelWithoutActiveOwnerButCallerDoesNot() {
        insertOrganizationAndParcel();

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), CADASTRAL_CODE);
        assertThat(summary).isPresent();
        assertThat(summary.orElseThrow().ownerId()).isNull();
        assertThat(summary.orElseThrow().owners()).isEmpty();
        assertThat(summary.orElseThrow().centroidLongitude()).isNotNull();
        assertThat(summary.orElseThrow().bboxWest()).isNotNull();

        assertThat(repository.landParcelsTile(ORG_ID, Optional.empty(), 8, 145, 75)).isNotEmpty();
        assertThat(repository.landParcelSummary(ORG_ID, Optional.of("caller-a"), CADASTRAL_CODE)).isEmpty();
        assertThat(repository.landParcelsTile(ORG_ID, Optional.of("caller-a"), 8, 145, 75)).isEmpty();
    }

    @Test
    void cadastralSummaryRepairsLegacyInvalidBoundaryForMapMetrics() {
        insertOrganizationAndParcel();
        dsl.execute("""
            UPDATE land_parcel
               SET boundary = ST_GeomFromText(
                   'POLYGON((24.70 59.40,24.71 59.41,24.71 59.40,24.70 59.41,24.70 59.40))', 4326)
             WHERE id = ?
            """, PARCEL_ID);

        var validity = dsl.fetchOne("SELECT ST_IsValid(boundary) AS valid FROM land_parcel WHERE id = ?", PARCEL_ID);
        assertThat(validity).isNotNull();
        assertThat(validity.get("valid", Boolean.class)).isFalse();

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), CADASTRAL_CODE).orElseThrow();
        assertThat(summary.centroidLongitude()).isNotNull();
        assertThat(summary.centroidLatitude()).isNotNull();
        assertThat(summary.bboxWest()).isNotNull();
        assertThat(summary.bboxNorth()).isNotNull();
    }

    @Test
    void multiOwnerStatusUsesBusinessPriorityAndCallerSeesOnlyOwnAssignment() {
        insertOrganizationAndParcel();
        insertOwner(OWNER_A_ID, "Caller A owner", "NEW", "caller-a", "50000001");
        insertOwner(OWNER_B_ID, "Caller B owner", "NEGOTIATION", "caller-b", "50000002");
        insertRelation(OWNER_A_ID);
        insertRelation(OWNER_B_ID);

        var unrestricted = repository.landParcelSummary(ORG_ID, Optional.empty(), CADASTRAL_CODE).orElseThrow();
        assertThat(unrestricted.ownerId()).isEqualTo(OWNER_B_ID);
        assertThat(unrestricted.ownerStatus()).isEqualTo("NEGOTIATION");
        assertThat(unrestricted.owners()).extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
            .containsExactly(OWNER_B_ID, OWNER_A_ID);

        var callerA = repository.landParcelSummary(ORG_ID, Optional.of("caller-a"), CADASTRAL_CODE).orElseThrow();
        assertThat(callerA.ownerId()).isEqualTo(OWNER_A_ID);
        assertThat(callerA.ownerStatus()).isEqualTo("NEW");
        assertThat(callerA.owners()).extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
            .containsExactly(OWNER_A_ID);

        var callerB = repository.landParcelSummary(ORG_ID, Optional.of("caller-b"), CADASTRAL_CODE).orElseThrow();
        assertThat(callerB.ownerId()).isEqualTo(OWNER_B_ID);
        assertThat(callerB.ownerStatus()).isEqualTo("NEGOTIATION");
        assertThat(callerB.owners()).extracting(ParcelMapSummary.ParcelOwnerSummary::ownerId)
            .containsExactly(OWNER_B_ID);
    }

    @Test
    void renderReadModelPrecomputesGeometryAndRefreshesOwnerVisibility() {
        insertOrganizationAndParcel();
        insertOwner(OWNER_A_ID, "Caller A owner", "NEW", "caller-a", "50000001");
        insertOwner(OWNER_B_ID, "Caller B owner", "NEGOTIATION", "caller-b", "50000002");
        insertRelation(OWNER_A_ID);
        insertRelation(OWNER_B_ID);

        var geometry = dsl.fetchOne("""
            SELECT ST_SRID(boundary_3857) AS srid,
                   ST_SRID(boundary_low_3857) AS low_srid,
                   ST_NPoints(boundary_low_3857) <= ST_NPoints(boundary_3857) AS simplified
              FROM land_parcel_render
             WHERE organization_id=? AND parcel_id=?
            """, ORG_ID, PARCEL_ID);
        assertThat(geometry).isNotNull();
        assertThat(geometry.get("srid", Integer.class)).isEqualTo(3857);
        assertThat(geometry.get("low_srid", Integer.class)).isEqualTo(3857);
        assertThat(geometry.get("simplified", Boolean.class)).isTrue();

        var allScope = dsl.fetchOne("""
            SELECT owner_id, owner_status, assigned_to_subject
              FROM land_parcel_render_owner
             WHERE organization_id=? AND parcel_id=? AND scope_key='ALL'
            """, ORG_ID, PARCEL_ID);
        assertThat(allScope).isNotNull();
        assertThat(allScope.get("owner_id", UUID.class)).isEqualTo(OWNER_B_ID);
        assertThat(allScope.get("owner_status", String.class)).isEqualTo("NEGOTIATION");

        var callerA = dsl.fetchOne("""
            SELECT owner_id FROM land_parcel_render_owner
             WHERE organization_id=? AND parcel_id=? AND scope_key='SUB:caller-a'
            """, ORG_ID, PARCEL_ID);
        assertThat(callerA).isNotNull();
        assertThat(callerA.get("owner_id", UUID.class)).isEqualTo(OWNER_A_ID);

        dsl.execute("""
            UPDATE owner SET status='CONTRACTED', assigned_to_subject='caller-c'
             WHERE organization_id=? AND id=?
            """, ORG_ID, OWNER_A_ID);

        var refreshedAll = dsl.fetchOne("""
            SELECT owner_id, owner_status FROM land_parcel_render_owner
             WHERE organization_id=? AND parcel_id=? AND scope_key='ALL'
            """, ORG_ID, PARCEL_ID);
        assertThat(refreshedAll).isNotNull();
        assertThat(refreshedAll.get("owner_id", UUID.class)).isEqualTo(OWNER_A_ID);
        assertThat(refreshedAll.get("owner_status", String.class)).isEqualTo("CONTRACTED");
        assertThat(dsl.fetchOne("""
            SELECT 1 FROM land_parcel_render_owner
             WHERE organization_id=? AND parcel_id=? AND scope_key='SUB:caller-a'
            """, ORG_ID, PARCEL_ID)).isNull();
        assertThat(dsl.fetchOne("""
            SELECT owner_id FROM land_parcel_render_owner
             WHERE organization_id=? AND parcel_id=? AND scope_key='SUB:caller-c'
            """, ORG_ID, PARCEL_ID).get("owner_id", UUID.class)).isEqualTo(OWNER_A_ID);

        assertThat(repository.landParcelsTile(ORG_ID, Optional.of("caller-a"), 8, 145, 75)).isEmpty();
        assertThat(repository.landParcelsTile(ORG_ID, Optional.of("caller-c"), 8, 145, 75)).isNotEmpty();
    }

    private void insertOrganizationAndParcel() {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", ORG_ID, "Map-first test org");
        dsl.execute("""
            INSERT INTO land_parcel (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
            VALUES (?, ?, NULL, ?, 10.0, ST_GeomFromText('POLYGON((24.70 59.40,24.71 59.40,24.71 59.41,24.70 59.41,24.70 59.40))', 4326))
            """, PARCEL_ID, ORG_ID, CADASTRAL_CODE);
    }

    private void insertOwner(UUID ownerId, String displayName, String status, String assignedSubject, String code) {
        dsl.execute("""
            INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, status, assigned_to_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            """, ownerId, ORG_ID, displayName, code, status, assignedSubject);
    }

    private void insertRelation(UUID ownerId) {
        dsl.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at)
            VALUES (?, ?, ?, 'TEST', TRUE, CURRENT_TIMESTAMP)
            """, ORG_ID, ownerId, PARCEL_ID);
    }
}
