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
class PreaggregatedMapTileRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_ID = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("bbbbbbbb-3333-3333-3333-333333333333");

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
    void overviewReadModelPreaggregatesAllAndSubjectScopesWithoutLeakage() {
        assertThat(repository).isInstanceOf(PreaggregatedMapTileRepository.class);
        dsl.execute("INSERT INTO organization (id,name) VALUES (?,?)", ORG_ID, "Overview test org");
        dsl.execute("""
            INSERT INTO land_parcel (id,organization_id,cadastral_code,area_hectares,boundary)
            VALUES (?,?,?,10.0,ST_GeomFromText(
                'POLYGON((24.70 59.40,24.71 59.40,24.71 59.41,24.70 59.41,24.70 59.40))',4326))
            """, PARCEL_ID, ORG_ID, "99998:001:0001");

        var allContribution = dsl.fetchOne("""
            SELECT tile_x,tile_y,grid_x,grid_y,owner_status_key
              FROM land_parcel_overview_contribution
             WHERE organization_id=? AND parcel_id=? AND scope_key='ALL' AND zoom=8
            """, ORG_ID, PARCEL_ID);
        assertThat(allContribution).isNotNull();
        assertThat(allContribution.get("owner_status_key", String.class)).isEqualTo("NONE");

        dsl.execute("""
            INSERT INTO owner (id,organization_id,display_name,personal_or_registry_code,status,assigned_to_subject)
            VALUES (?,?,'Caller owner','59999999','NEW','caller-a')
            """, OWNER_ID, ORG_ID);
        dsl.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id,owner_id,parcel_id,source,active,source_seen_at)
            VALUES (?,?,?,'TEST',true,CURRENT_TIMESTAMP)
            """, ORG_ID, OWNER_ID, PARCEL_ID);

        var allAfterOwner = dsl.fetchOne("""
            SELECT tile_x,tile_y,owner_status_key
              FROM land_parcel_overview_contribution
             WHERE organization_id=? AND parcel_id=? AND scope_key='ALL' AND zoom=8
            """, ORG_ID, PARCEL_ID);
        var subjectContribution = dsl.fetchOne("""
            SELECT tile_x,tile_y,owner_status_key
              FROM land_parcel_overview_contribution
             WHERE organization_id=? AND parcel_id=? AND scope_key='SUB:caller-a' AND zoom=8
            """, ORG_ID, PARCEL_ID);
        assertThat(allAfterOwner).isNotNull();
        assertThat(subjectContribution).isNotNull();
        assertThat(allAfterOwner.get("owner_status_key", String.class)).isEqualTo("NEW");
        assertThat(subjectContribution.get("owner_status_key", String.class)).isEqualTo("NEW");

        int x = allAfterOwner.get("tile_x", Integer.class);
        int y = allAfterOwner.get("tile_y", Integer.class);
        assertThat(repository.landParcelsOverviewTile(ORG_ID, Optional.empty(), 8, x, y)).isNotEmpty();
        assertThat(repository.landParcelsOverviewTile(ORG_ID, Optional.of("caller-a"), 8, x, y)).isNotEmpty();
        assertThat(repository.landParcelsOverviewTile(ORG_ID, Optional.of("caller-b"), 8, x, y)).isEmpty();
    }
}
