package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MetsaregisterWfsVerificationMaintenanceIntegrationTest {
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

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
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_generation_id=NULL, current_row_count=0, provider_number_matched=NULL, state='PENDING_SCHEMA', last_verified_at=NULL, last_verification_state=NULL, last_verified_local_count=NULL, last_verified_provider_count=NULL");
        dsl.execute("DELETE FROM metsaregister_wfs_generation");
    }

    @Test
    void deepVerificationPersistsLocalVsProviderCompleteness() {
        UUID generation = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation
                (id, layer_name, state, rows_staged, provider_number_matched, completed_at)
            VALUES (?, 'metsaregister:eraldis', 'PUBLISHED', 2, 2, CURRENT_TIMESTAMP)
            """, generation);
        dsl.execute("""
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_hash, properties)
            VALUES
                (?, 'metsaregister:eraldis', 'one', repeat('a',64), '{}'::jsonb),
                (?, 'metsaregister:eraldis', 'two', repeat('b',64), '{}'::jsonb)
            """, generation, generation);
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET approved=TRUE, enabled=TRUE, provider_present=TRUE, state='HEALTHY',
                   current_generation_id=?, current_row_count=2, provider_number_matched=2
             WHERE layer_name='metsaregister:eraldis'
            """, generation);

        var report = new MetsaregisterWfsVerificationService(dsl).verify(true);

        var layer = report.layers().stream()
            .filter(item -> item.layerName().equals("metsaregister:eraldis"))
            .findFirst().orElseThrow();
        assertThat(layer.completeness()).isEqualTo("COMPLETE");
        assertThat(layer.actualFeatureRows()).isEqualTo(2L);
        var recorded = dsl.fetchOne("""
            SELECT last_verification_state, last_verified_local_count, last_verified_provider_count
              FROM metsaregister_wfs_layer
             WHERE layer_name='metsaregister:eraldis'
            """);
        assertThat(recorded.get("last_verification_state", String.class)).isEqualTo("COMPLETE");
        assertThat(recorded.get("last_verified_local_count", Long.class)).isEqualTo(2L);
        assertThat(recorded.get("last_verified_provider_count", Long.class)).isEqualTo(2L);
    }

    @Test
    void cleanupDeletesOnlyExpiredRetiredGenerationsAndCascadesFeatures() {
        UUID current = UUID.randomUUID();
        UUID oldRetired = UUID.randomUUID();
        UUID recentRetired = UUID.randomUUID();
        seedGeneration(current, "PUBLISHED", Instant.now().minusSeconds(86_400));
        seedGeneration(oldRetired, "RETIRED", Instant.now().minusSeconds(30L * 86_400));
        seedGeneration(recentRetired, "RETIRED", Instant.now().minusSeconds(2L * 86_400));
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_generation_id=?, current_row_count=0, state='HEALTHY'
             WHERE layer_name='metsaregister:eraldis'
            """, current);
        seedFeature(current, "current");
        seedFeature(oldRetired, "old-retired");
        seedFeature(recentRetired, "recent-retired");

        var result = new MetsaregisterWfsGenerationMaintenanceService(dsl, 14).cleanupRetired();

        assertThat(result.generationsDeleted()).isEqualTo(1);
        assertThat(result.featureRowsDeleted()).isEqualTo(1);
        assertThat(generationExists(current)).isTrue();
        assertThat(generationExists(oldRetired)).isFalse();
        assertThat(generationExists(recentRetired)).isTrue();
        assertThat(featureExists("old-retired")).isFalse();
        assertThat(featureExists("recent-retired")).isTrue();
    }

    private void seedGeneration(UUID id, String state, Instant completedAt) {
        dsl.execute("""
            INSERT INTO metsaregister_wfs_generation
                (id, layer_name, state, rows_staged, provider_number_matched, completed_at)
            VALUES (?, 'metsaregister:eraldis', ?, 0, 0, CAST(? AS timestamptz))
            """, id, state, completedAt);
    }

    private void seedFeature(UUID generation, String key) {
        dsl.execute("""
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_hash, properties)
            VALUES (?, 'metsaregister:eraldis', ?, repeat('c',64), '{}'::jsonb)
            """, generation, key);
    }

    private boolean generationExists(UUID id) {
        return dsl.fetchExists(dsl.selectOne()
            .from("metsaregister_wfs_generation")
            .where("id = ?", id));
    }

    private boolean featureExists(String key) {
        return dsl.fetchExists(dsl.selectOne()
            .from("metsaregister_wfs_feature")
            .where("feature_key = ?", key));
    }
}
