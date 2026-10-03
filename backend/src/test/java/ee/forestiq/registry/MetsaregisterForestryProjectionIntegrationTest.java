package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;

@Testcontainers(disabledWithoutDocker = true)
class MetsaregisterForestryProjectionIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;
    private MetsaregisterLayerManifestService manifest;
    private MetsaregisterForestryProjectionService projection;

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
        manifest = new MetsaregisterLayerManifestService(new ObjectMapper());
        projection = new MetsaregisterForestryProjectionService(
            dsl, manifest, new GeospatialSourceGenerationRepository(dsl), new ObjectMapper());

        resetCanonical();
        resetRawMirror();
        seedAllLocalLayerGenerations();
    }

    @Test
    void publishesOneCanonicalGenerationAcrossAllLocalLayers() {
        seedFeature("metsaregister:teatis", "notice-1",
            "{\"sys_id\":1,\"katastri_nr\":\"77777:001:0001\",\"teatise_nr\":\"MT-1\",\"pindala\":\"1.25\"}",
            "ST_Transform(ST_GeomFromText('POLYGON((24.700 59.400,24.705 59.400,24.705 59.405,24.700 59.405,24.700 59.400))',4326),3301)");
        seedFeature("metsaregister:eraldis", "compartment-10",
            "{\"sys_id\":10,\"katastri_nr\":\"77777:001:0001\",\"eraldise_nr\":\"12\",\"pindala\":\"2.5\"}",
            "ST_Transform(ST_GeomFromText('POLYGON((24.700 59.400,24.710 59.400,24.710 59.410,24.700 59.410,24.700 59.400))',4326),3301)");
        seedFeature("metsaregister:eraldis_element", "element-1",
            "{\"sys_id\":101,\"eraldis_id\":\"10\",\"puuliik\":\"KU\"}", null);

        var result = projection.refresh("MANUAL", "integration-test");

        assertThat(result.changed()).isTrue();
        assertThat(result.outcome()).isEqualTo("PUBLISHED");
        assertThat(result.status().ready()).isTrue();
        assertThat(result.status().inSync()).isTrue();
        assertThat(result.status().rawLayers()).hasSize(manifest.approvedLocalLayerNames().size());

        Integer sourceCount = dsl.fetchOne("""
            SELECT count(*)::int AS c
              FROM forestry_generation_source
             WHERE generation_id=?
            """, result.generationId()).get("c", Integer.class);
        assertThat(sourceCount).isEqualTo(manifest.approvedLocalLayerNames().size());

        var compartment = dsl.fetchOne("""
            SELECT generation_id, cadastral_code, subpart_code,
                   ST_SRID(geom) AS srid,
                   jsonb_array_length(elements) AS element_count
              FROM forestry_current_compartment_detail
             WHERE compartment_id='10'
            """);
        assertThat(compartment).isNotNull();
        assertThat(compartment.get("generation_id", UUID.class)).isEqualTo(result.generationId());
        assertThat(compartment.get("cadastral_code", String.class)).isEqualTo("77777:001:0001");
        assertThat(compartment.get("subpart_code", Long.class)).isEqualTo(12L);
        assertThat(compartment.get("srid", Integer.class)).isEqualTo(3301);
        assertThat(compartment.get("element_count", Integer.class)).isEqualTo(1);

        var notification = dsl.fetchOne("""
            SELECT generation_id, notification_number, cadastral_code, area
              FROM forestry_current_notification
             WHERE notification_number='MT-1'
            """);
        assertThat(notification).isNotNull();
        assertThat(notification.get("generation_id", UUID.class)).isEqualTo(result.generationId());
        assertThat(notification.get("cadastral_code", String.class)).isEqualTo("77777:001:0001");

        var unchanged = projection.refresh("MANUAL", "integration-test");
        assertThat(unchanged.changed()).isFalse();
        assertThat(unchanged.generationId()).isEqualTo(result.generationId());
    }

    @Test
    void preservesLastKnownGoodRollbackGenerationAndDetectsSourceDrift() {
        seedFeature("metsaregister:teatis", "notice-v1",
            "{\"sys_id\":1,\"katastri_nr\":\"77777:001:0001\",\"teatise_nr\":\"MT-1\"}", null);
        UUID firstCanonical = projection.refresh("MANUAL", "integration-test").generationId();

        replaceRawGeneration("metsaregister:teatis", "notice-v2",
            "{\"sys_id\":2,\"katastri_nr\":\"77777:001:0001\",\"teatise_nr\":\"MT-2\"}");
        UUID secondCanonical = projection.refresh("MANUAL", "integration-test").generationId();

        assertThat(secondCanonical).isNotEqualTo(firstCanonical);
        assertThat(dsl.fetchOne("SELECT status FROM geospatial_generation WHERE id=?", firstCanonical)
            .get("status", String.class)).isEqualTo("RETIRED");
        assertThat(projection.status().inSync()).isTrue();

        assertThatThrownBy(() -> dsl.execute("DELETE FROM geospatial_generation WHERE id=?", firstCanonical))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("last known-good forestry rollback generation");

        var rolledBack = projection.rollback(firstCanonical, "integration-test", "verify rollback");
        assertThat(rolledBack.currentGenerationId()).isEqualTo(firstCanonical);
        assertThat(rolledBack.inSync()).isFalse();
        assertThat(rolledBack.driftedLayers()).contains("metsaregister:teatis");
        assertThat(dsl.fetchOne("SELECT status FROM geospatial_generation WHERE id=?", secondCanonical)
            .get("status", String.class)).isEqualTo("RETIRED");
    }

    private void resetCanonical() {
        dsl.execute("DELETE FROM geospatial_source_current WHERE source_key=?",
            MetsaregisterForestryProjectionService.SOURCE_KEY);
        dsl.execute("DELETE FROM geospatial_generation_event WHERE source_key=?",
            MetsaregisterForestryProjectionService.SOURCE_KEY);
        dsl.execute("""
            UPDATE geospatial_generation
               SET status='FAILED', failed_at=COALESCE(failed_at, CURRENT_TIMESTAMP), retired_at=NULL
             WHERE source_key=?
            """, MetsaregisterForestryProjectionService.SOURCE_KEY);
        dsl.execute("DELETE FROM geospatial_generation WHERE source_key=?",
            MetsaregisterForestryProjectionService.SOURCE_KEY);
        dsl.execute("DELETE FROM geospatial_source_run WHERE source_key=?",
            MetsaregisterForestryProjectionService.SOURCE_KEY);
    }

    private void resetRawMirror() {
        dsl.execute("DELETE FROM metsaregister_wfs_feature");
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_generation_id=NULL, current_row_count=0");
        dsl.execute("DELETE FROM metsaregister_wfs_generation");
    }

    private void seedAllLocalLayerGenerations() {
        for (String layer : manifest.approvedLocalLayerNames()) {
            UUID generation = UUID.randomUUID();
            dsl.execute("""
                UPDATE metsaregister_wfs_layer
                   SET approved=TRUE,
                       enabled=TRUE,
                       consumption_mode='LOCAL_DOMAIN_DATA',
                       provider_present=TRUE,
                       state='HEALTHY',
                       current_row_count=0
                 WHERE layer_name=?
                """, layer);
            dsl.execute("""
                INSERT INTO metsaregister_wfs_generation
                    (id, layer_name, state, next_start_index, pages_processed, rows_staged, completed_at)
                VALUES (?, ?, 'PUBLISHED', 0, 0, 0, CURRENT_TIMESTAMP)
                """, generation, layer);
            dsl.execute("""
                UPDATE metsaregister_wfs_layer
                   SET current_generation_id=?
                 WHERE layer_name=?
                """, generation, layer);
        }
    }

    private void seedFeature(String layer, String featureKey, String propertiesJson, String geometryExpression) {
        UUID generation = currentRawGeneration(layer);
        if (geometryExpression == null) {
            dsl.execute("""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                VALUES (?, ?, ?, ?, repeat('a',64), ?::jsonb, NULL)
                """, generation, layer, featureKey, featureKey, propertiesJson);
        } else {
            dsl.execute("""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                VALUES (?, ?, ?, ?, repeat('a',64), ?::jsonb, %s)
                """.formatted(geometryExpression), generation, layer, featureKey, featureKey, propertiesJson);
        }
        dsl.execute("UPDATE metsaregister_wfs_generation SET rows_staged=rows_staged+1 WHERE id=?", generation);
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_row_count=current_row_count+1 WHERE layer_name=?", layer);
    }

    private void replaceRawGeneration(String layer, String featureKey, String propertiesJson) {
        UUID previous = currentRawGeneration(layer);
        dsl.execute("UPDATE metsaregister_wfs_generation SET state='RETIRED' WHERE id=?", previous);
        UUID generation = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation
                (id, layer_name, state, next_start_index, pages_processed, rows_staged, completed_at)
            VALUES (?, ?, 'PUBLISHED', 1, 1, 1, CURRENT_TIMESTAMP)
            """, generation, layer);
        dsl.execute("""
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
            VALUES (?, ?, ?, ?, repeat('b',64), ?::jsonb, NULL)
            """, generation, layer, featureKey, featureKey, propertiesJson);
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_generation_id=?, current_row_count=1, state='HEALTHY'
             WHERE layer_name=?
            """, generation, layer);
    }

    private UUID currentRawGeneration(String layer) {
        return dsl.fetchOne("SELECT current_generation_id FROM metsaregister_wfs_layer WHERE layer_name=?", layer)
            .get("current_generation_id", UUID.class);
    }
}
