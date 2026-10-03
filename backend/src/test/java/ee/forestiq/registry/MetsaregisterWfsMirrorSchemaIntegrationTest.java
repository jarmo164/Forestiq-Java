package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MetsaregisterWfsMirrorSchemaIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID TEST_ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEST_PARCEL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String TEST_CADASTRAL_CODE = "78401:101:9999";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl.configuration().set(SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM owner WHERE id = ?", TEST_OWNER_ID);
        dsl.execute("DELETE FROM metsaregister_wfs_feature");
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_generation_id=NULL, current_row_count=0");
        dsl.execute("DELETE FROM metsaregister_wfs_generation");
    }

    @Test
    void providerMirrorTablesAreGlobalRatherThanTenantDuplicated() {
        Integer organizationColumns = dsl.fetchOne("""
            SELECT count(*)::int AS c
              FROM information_schema.columns
             WHERE table_schema='public'
               AND table_name IN ('metsaregister_wfs_layer','metsaregister_wfs_generation','metsaregister_wfs_feature')
               AND column_name='organization_id'
            """).get("c", Integer.class);

        assertThat(organizationColumns).isZero();
    }

    @Test
    void featureMirrorUsesCanonicalEpsg3301GeometryWithSpatialIndex() {
        UUID generation = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation (id, layer_name, state)
            VALUES (?, 'metsaregister:eraldis', 'STAGING')
            """, generation);
        dsl.execute("""
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
            VALUES (?, 'metsaregister:eraldis', '42', '42', repeat('a',64),
                    '{"sys_id":42,"name":"sample"}'::jsonb,
                    ST_Transform(ST_SetSRID(ST_Point(24.7,59.4),4326),3301))
            """, generation);

        var row = dsl.fetchOne("""
            SELECT properties->>'name' AS name, ST_SRID(geometry) AS srid
              FROM metsaregister_wfs_feature
             WHERE generation_id=? AND feature_key='42'
            """, generation);
        String indexDefinition = dsl.fetchOne("""
            SELECT indexdef
              FROM pg_indexes
             WHERE schemaname='public' AND indexname='metsaregister_wfs_feature_geometry_gix'
            """).get("indexdef", String.class);
        var sourceContract = dsl.fetchOne("""
            SELECT expected_source_srid, canonical_srid
              FROM geospatial_source
             WHERE source_key='METSAREGISTER_FORESTRY'
            """);

        assertThat(row.get("name", String.class)).isEqualTo("sample");
        assertThat(row.get("srid", Integer.class)).isEqualTo(3301);
        assertThat(indexDefinition.toLowerCase()).contains("using gist");
        assertThat(sourceContract.get("expected_source_srid", Integer.class)).isEqualTo(4326);
        assertThat(sourceContract.get("canonical_srid", Integer.class)).isEqualTo(3301);
    }

    @Test
    void parcelIntersectionTransformsTenantBoundaryIntoCanonicalForestryCrs() {
        dsl.execute("""
            INSERT INTO owner
                (id, organization_id, display_name, personal_or_registry_code)
            VALUES (?, ?, 'CRS integration owner', 'CRS-TEST-OWNER')
            """, TEST_OWNER_ID, TEST_ORGANIZATION_ID);
        dsl.execute("""
            INSERT INTO land_parcel
                (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
            VALUES (?, ?, ?, ?, 1.0,
                    ST_GeomFromText(
                        'POLYGON((24.753 59.437,24.763 59.437,24.763 59.442,24.753 59.442,24.753 59.437))',
                        4326))
            """, TEST_PARCEL_ID, TEST_ORGANIZATION_ID, TEST_OWNER_ID, TEST_CADASTRAL_CODE);

        UUID generation = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation (id, layer_name, state)
            VALUES (?, 'metsaregister:eraldis', 'PUBLISHED')
            """, generation);
        dsl.execute("""
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
            VALUES (?, 'metsaregister:eraldis', 'inside-test-parcel', 'inside-test-parcel', repeat('b',64),
                    '{"sys_id":"inside-test-parcel"}'::jsonb,
                    ST_Transform(ST_SetSRID(ST_Point(24.758,59.439),4326),3301))
            """, generation);
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_generation_id=?, current_row_count=1
             WHERE layer_name='metsaregister:eraldis'
            """, generation);

        var row = dsl.fetchOne("""
            SELECT feature_key, ST_SRID(geometry) AS srid
              FROM metsaregister_wfs_features_for_parcel(?, ?, ARRAY['metsaregister:eraldis']::text[])
             WHERE feature_key='inside-test-parcel'
            """, TEST_ORGANIZATION_ID, TEST_CADASTRAL_CODE);

        assertThat(row).isNotNull();
        assertThat(row.get("feature_key", String.class)).isEqualTo("inside-test-parcel");
        assertThat(row.get("srid", Integer.class)).isEqualTo(3301);
    }

    @Test
    void onlyOneResumableStagingGenerationCanExistPerLayer() {
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation (id, layer_name, state)
            VALUES (?, 'metsaregister:eraldis', 'STAGING')
            """, UUID.randomUUID());

        assertThatThrownBy(() -> dsl.execute("""
            INSERT INTO metsaregister_wfs_generation (id, layer_name, state)
            VALUES (?, 'metsaregister:eraldis', 'STAGING')
            """, UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void currentGenerationPointerCannotReferenceAnotherLayer() {
        UUID eraldis = UUID.randomUUID();
        UUID teatis = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation (id, layer_name, state)
            VALUES (?, 'metsaregister:eraldis', 'PUBLISHED'),
                   (?, 'metsaregister:teatis', 'PUBLISHED')
            """, eraldis, teatis);
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_generation_id=?
             WHERE layer_name='metsaregister:eraldis'
            """, eraldis);

        assertThatThrownBy(() -> dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_generation_id=?
             WHERE layer_name='metsaregister:eraldis'
            """, teatis))
            .isInstanceOf(DataAccessException.class);

        UUID current = dsl.fetchOne("""
            SELECT current_generation_id
              FROM metsaregister_wfs_layer
             WHERE layer_name='metsaregister:eraldis'
            """).get("current_generation_id", UUID.class);
        assertThat(current).isEqualTo(eraldis);
    }
}
