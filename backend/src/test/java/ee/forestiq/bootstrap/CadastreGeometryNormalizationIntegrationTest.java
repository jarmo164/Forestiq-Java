package ee.forestiq.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import ee.forestiq.cadastre.MapTileRepository;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
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
class CadastreGeometryNormalizationIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_ID = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID PARCEL_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final String CADASTRAL_CODE = "78401:101:5214";
    private static final String SWAPPED_PROVIDER_GEOMETRY = """
        {"type":"Polygon","coordinates":[[
          [59.40,24.70],
          [59.40,24.71],
          [59.41,24.71],
          [59.41,24.70],
          [59.40,24.70]
        ]]}
        """;

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
    void normalizesSwappedWfsAxesBeforeSnapshotStorage() {
        UUID snapshotId = insertSnapshot();
        String rawFeature = "{\"type\":\"Feature\",\"geometry\":"
            + SWAPPED_PROVIDER_GEOMETRY + ",\"properties\":{}}";

        dsl.execute("""
            INSERT INTO external_dataset_feature
                (source_key, snapshot_id, feature_key, source_id, cadastral_code,
                 properties, geometry, raw_feature)
            VALUES ('MAAAMET_CADASTRE', ?, ?, ?, ?, '{}'::jsonb, ?::jsonb, ?::jsonb)
            """, snapshotId, CADASTRAL_CODE, CADASTRAL_CODE, CADASTRAL_CODE,
            SWAPPED_PROVIDER_GEOMETRY, rawFeature);

        var coordinates = dsl.fetchOne("""
            SELECT ST_XMin(Box2D(g)) AS west,
                   ST_XMax(Box2D(g)) AS east,
                   ST_YMin(Box2D(g)) AS south,
                   ST_YMax(Box2D(g)) AS north
              FROM external_dataset_feature f
              CROSS JOIN LATERAL (
                  SELECT ST_SetSRID(ST_GeomFromGeoJSON(f.geometry::text), 4326) AS g
              ) normalized
             WHERE f.source_key = 'MAAAMET_CADASTRE'
               AND f.snapshot_id = ?
               AND f.feature_key = ?
            """, snapshotId, CADASTRAL_CODE);

        assertThat(coordinates).isNotNull();
        assertThat(coordinates.get("west", Double.class)).isBetween(24.69, 24.71);
        assertThat(coordinates.get("east", Double.class)).isBetween(24.70, 24.72);
        assertThat(coordinates.get("south", Double.class)).isBetween(59.39, 59.41);
        assertThat(coordinates.get("north", Double.class)).isBetween(59.40, 59.42);

        var raw = dsl.fetchOne("""
            SELECT (raw_feature->'geometry'->'coordinates'->0->0->>0)::double precision AS first_x
              FROM external_dataset_feature
             WHERE source_key = 'MAAAMET_CADASTRE'
               AND snapshot_id = ?
               AND feature_key = ?
            """, snapshotId, CADASTRAL_CODE);
        assertThat(raw).isNotNull();
        assertThat(raw.get("first_x", Double.class)).isEqualTo(59.40);
    }

    @Test
    void rejectsCadastreGeometryOutsideEstoniaInEitherAxisOrder() {
        UUID snapshotId = insertSnapshot();
        String outside = """
            {"type":"Polygon","coordinates":[[
              [10.0,10.0],
              [10.0,10.1],
              [10.1,10.1],
              [10.1,10.0],
              [10.0,10.0]
            ]]}
            """;

        assertThatThrownBy(() -> dsl.execute("""
            INSERT INTO external_dataset_feature
                (source_key, snapshot_id, feature_key, source_id, cadastral_code,
                 properties, geometry, raw_feature)
            VALUES ('MAAAMET_CADASTRE', ?, 'outside', 'outside', '00000:000:0000',
                    '{}'::jsonb, ?::jsonb, '{}'::jsonb)
            """, snapshotId, outside))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("outside Estonia");
    }

    @Test
    void correctedCanonicalGeometryFeedsEstoniaMvtAndNotIndianOceanTile() {
        insertOrganizationAndParcel();
        UUID snapshotId = insertSnapshot();

        dsl.execute("""
            INSERT INTO external_dataset_feature
                (source_key, snapshot_id, feature_key, source_id, cadastral_code,
                 properties, geometry, raw_feature)
            VALUES ('MAAAMET_CADASTRE', ?, ?, ?, ?, '{}'::jsonb, ?::jsonb, '{}'::jsonb)
            """, snapshotId, CADASTRAL_CODE, CADASTRAL_CODE, CADASTRAL_CODE,
            SWAPPED_PROVIDER_GEOMETRY);
        dsl.execute("""
            INSERT INTO external_dataset_current (source_key, snapshot_id, published_at)
            VALUES ('MAAAMET_CADASTRE', ?, CURRENT_TIMESTAMP)
            """, snapshotId);

        dsl.execute("""
            UPDATE land_parcel p
               SET boundary = ST_SetSRID(
                       ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(f.geometry::text))),
                       4326
                   ),
                   updated_at = CURRENT_TIMESTAMP
              FROM external_dataset_current c
              JOIN external_dataset_feature f
                ON f.source_key = c.source_key
               AND f.snapshot_id = c.snapshot_id
             WHERE c.source_key = 'MAAAMET_CADASTRE'
               AND p.organization_id = ?
               AND p.cadastral_code = f.cadastral_code
            """, ORG_ID);

        var canonical = dsl.fetchOne("""
            SELECT ST_SRID(boundary) AS srid,
                   ST_X(ST_PointOnSurface(boundary)) AS lon,
                   ST_Y(ST_PointOnSurface(boundary)) AS lat
              FROM land_parcel
             WHERE organization_id = ? AND id = ?
            """, ORG_ID, PARCEL_ID);
        assertThat(canonical).isNotNull();
        assertThat(canonical.get("srid", Integer.class)).isEqualTo(4326);
        assertThat(canonical.get("lon", Double.class)).isBetween(24.69, 24.72);
        assertThat(canonical.get("lat", Double.class)).isBetween(59.39, 59.42);

        var render = dsl.fetchOne("""
            SELECT ST_SRID(boundary_3857) AS srid
              FROM land_parcel_render
             WHERE organization_id = ? AND parcel_id = ?
            """, ORG_ID, PARCEL_ID);
        assertThat(render).isNotNull();
        assertThat(render.get("srid", Integer.class)).isEqualTo(3857);

        var summary = repository.landParcelSummary(ORG_ID, Optional.empty(), CADASTRAL_CODE).orElseThrow();
        assertThat(summary.centroidLongitude()).isBetween(24.69, 24.72);
        assertThat(summary.centroidLatitude()).isBetween(59.39, 59.42);
        assertThat(summary.bboxWest()).isBetween(24.69, 24.71);
        assertThat(summary.bboxNorth()).isBetween(59.40, 59.42);

        assertThat(repository.landParcelsTile(ORG_ID, Optional.empty(), 8, 145, 75)).isNotEmpty();
        assertThat(repository.landParcelsTile(ORG_ID, Optional.empty(), 8, 170, 109)).isEmpty();
    }

    private UUID insertSnapshot() {
        UUID snapshotId = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO external_dataset_snapshot_run
                (id, source_key, source_url, type_name, status)
            VALUES (?, 'MAAAMET_CADASTRE', 'https://gsavalik.envir.ee/geoserver/kataster/wfs',
                    'kataster:ky_kehtiv', 'RUNNING')
            """, snapshotId);
        return snapshotId;
    }

    private void insertOrganizationAndParcel() {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", ORG_ID, "Cadastre CRS test org");
        dsl.execute("""
            INSERT INTO land_parcel
                (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
            VALUES (?, ?, NULL, ?, 1.0, NULL)
            """, PARCEL_ID, ORG_ID, CADASTRAL_CODE);
    }
}
