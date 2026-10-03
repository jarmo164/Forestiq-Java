package ee.forestiq.geospatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cache.type=simple",
        "spring.quartz.auto-startup=false",
        "forestiq.cors.allowed-origins=http://localhost:3000",
        "forestiq.scheduling.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("core")
class GeospatialSourceGenerationRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

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
    @Autowired private GeospatialSourceGenerationRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void resetGeospatialControlPlane() {
        dsl.execute("DELETE FROM geospatial_generation_event");
        dsl.execute("DELETE FROM geospatial_source_current");
        dsl.execute("DELETE FROM geospatial_generation");
        dsl.execute("DELETE FROM geospatial_source_run");
        dsl.execute("DELETE FROM geospatial_source");

        insertSource("MARU_CADASTRE", "cadastre", 3301, 3301);
        insertSource("MARU_ETAK_FOREST", "etak-puittaimestik", 3301, 3301);
    }

    @Test
    void publishesNewGenerationAndRollsBackWithoutReconstructingSourceData() {
        UUID firstRun = repository.startRun("MARU_CADASTRE", "BOOTSTRAP");
        UUID firstGeneration = repository.createStagingGeneration(
            firstRun,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 5),
            "2026-09-05",
            "sha256:first",
            178_000_000L,
            "schema-v1",
            3301,
            3301);
        repository.markValidated(firstGeneration, 780_000, 0, 0, "{\"estoniaGuard\":true}");
        repository.publishValidated("MARU_CADASTRE", firstGeneration, "test-admin", "Initial publication");

        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isEqualTo(firstGeneration);
        assertThat(status(firstGeneration)).isEqualTo("PUBLISHED");
        assertThat(runStatus(firstRun)).isEqualTo("SUCCESS");
        assertThat(repository.hasAcceptedArtifactChecksum("MARU_CADASTRE", "sha256:first")).isTrue();

        UUID secondRun = repository.startRun("MARU_CADASTRE", "SCHEDULED");
        UUID secondGeneration = repository.createStagingGeneration(
            secondRun,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "2026-09-06",
            "sha256:second",
            179_000_000L,
            "schema-v1",
            3301,
            3301);
        repository.markValidated(secondGeneration, 780_100, 0, 2, "{\"estoniaGuard\":true}");
        repository.publishValidated("MARU_CADASTRE", secondGeneration, "test-admin", "Daily snapshot");

        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isEqualTo(secondGeneration);
        assertThat(status(firstGeneration)).isEqualTo("RETIRED");
        assertThat(status(secondGeneration)).isEqualTo("PUBLISHED");
        assertThat(runStatus(secondRun)).isEqualTo("SUCCESS");

        repository.rollbackToRetired(
            "MARU_CADASTRE",
            firstGeneration,
            "test-admin",
            "Map validation detected a cutover regression");

        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isEqualTo(firstGeneration);
        assertThat(status(firstGeneration)).isEqualTo("PUBLISHED");
        assertThat(status(secondGeneration)).isEqualTo("RETIRED");

        List<String> firstEvents = eventTypes(firstGeneration);
        List<String> secondEvents = eventTypes(secondGeneration);
        assertThat(firstEvents).containsExactly("CREATED", "VALIDATED", "PUBLISHED", "RETIRED", "ROLLED_BACK");
        assertThat(secondEvents).containsExactly("CREATED", "VALIDATED", "PUBLISHED", "RETIRED");
    }

    @Test
    void recordsPartialRunAndMakesItsGenerationUnpublishable() {
        UUID runId = repository.startRun("MARU_CADASTRE", "SCHEDULED");
        UUID generationId = repository.createStagingGeneration(
            runId,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "partial-source",
            "sha256:partial",
            50L,
            "schema-v1",
            3301,
            3301);

        repository.finishUnpublishedRun(
            runId,
            "PARTIAL",
            new GeospatialSourceGenerationRepository.RunCounters(
                100, 90, 0, 0, 0, 0, 10, 2),
            "Provider stream ended before the complete snapshot was validated");

        assertThat(runStatus(runId)).isEqualTo("PARTIAL");
        assertThat(status(generationId)).isEqualTo("FAILED");
        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isNull();
        assertThat(repository.hasAcceptedArtifactChecksum("MARU_CADASTRE", "sha256:partial")).isFalse();
        assertThat(eventTypes(generationId)).containsExactly("CREATED", "FAILED");

        Integer rejected = jdbcTemplate.queryForObject(
            "SELECT rows_rejected::int FROM geospatial_source_run WHERE id = ?",
            Integer.class,
            runId);
        assertThat(rejected).isEqualTo(10);
    }

    @Test
    void failedGenerationDoesNotBlockRetryingTheSameArtifactChecksum() {
        UUID firstRun = repository.startRun("MARU_CADASTRE", "SCHEDULED");
        UUID failedGeneration = repository.createStagingGeneration(
            firstRun,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "retry-source",
            "sha256:retry",
            123L,
            "schema-v1",
            3301,
            3301);
        repository.finishUnpublishedRun(
            firstRun,
            "FAILED",
            GeospatialSourceGenerationRepository.RunCounters.zero(),
            "Temporary disk failure");

        assertThat(status(failedGeneration)).isEqualTo("FAILED");
        assertThat(repository.hasAcceptedArtifactChecksum("MARU_CADASTRE", "sha256:retry")).isFalse();

        UUID retryRun = repository.startRun("MARU_CADASTRE", "RECOVERY");
        UUID retryGeneration = repository.createStagingGeneration(
            retryRun,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "retry-source",
            "sha256:retry",
            123L,
            "schema-v1",
            3301,
            3301);
        repository.markValidated(retryGeneration, 1, 0, 0, "{}");

        assertThat(status(retryGeneration)).isEqualTo("VALIDATED");
        assertThat(repository.hasAcceptedArtifactChecksum("MARU_CADASTRE", "sha256:retry")).isTrue();

        String runChecksum = jdbcTemplate.queryForObject(
            "SELECT artifact_checksum FROM geospatial_source_run WHERE id = ?",
            String.class,
            retryRun);
        assertThat(runChecksum).isEqualTo("sha256:retry");
    }

    @Test
    void refusesToPublishUnvalidatedGeneration() {
        UUID runId = repository.startRun("MARU_CADASTRE", "MANUAL");
        UUID generationId = repository.createStagingGeneration(
            runId,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "manual-check",
            "sha256:staging",
            10L,
            "schema-v1",
            3301,
            3301);

        assertThatThrownBy(() -> repository.publishValidated(
            "MARU_CADASTRE", generationId, "test-admin", "Must fail"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("VALIDATED");

        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isNull();
        assertThat(status(generationId)).isEqualTo("STAGING");
        assertThat(runStatus(runId)).isEqualTo("RUNNING");
    }

    @Test
    void refusesCrossSourcePublicationAndCurrentPointerCannotTargetValidatedOnlyGeneration() {
        UUID cadastreRun = repository.startRun("MARU_CADASTRE", "MANUAL");
        UUID cadastreGeneration = repository.createStagingGeneration(
            cadastreRun,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            "cadastre",
            "sha256:cadastre",
            10L,
            "schema-v1",
            3301,
            3301);
        repository.markValidated(cadastreGeneration, 1, 0, 0, "{}");

        assertThatThrownBy(() -> repository.publishValidated(
            "MARU_ETAK_FOREST", cadastreGeneration, "test-admin", "Wrong source"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO geospatial_source_current (source_key, generation_id)
            VALUES (?, ?)
            """, "MARU_CADASTRE", cadastreGeneration))
            .isInstanceOf(RuntimeException.class);

        assertThat(repository.currentGenerationId("MARU_CADASTRE")).isNull();
        assertThat(status(cadastreGeneration)).isEqualTo("VALIDATED");
    }

    @Test
    void permitsOnlyOneActiveRunPerSource() {
        UUID firstRun = repository.startRun("MARU_CADASTRE", "SCHEDULED");

        assertThatThrownBy(() -> repository.startRun("MARU_CADASTRE", "MANUAL"))
            .isInstanceOf(RuntimeException.class);

        assertThat(runStatus(firstRun)).isEqualTo("RUNNING");
        Integer activeRuns = jdbcTemplate.queryForObject("""
            SELECT count(*)
              FROM geospatial_source_run
             WHERE source_key = 'MARU_CADASTRE'
               AND status IN ('QUEUED', 'RUNNING')
            """, Integer.class);
        assertThat(activeRuns).isEqualTo(1);
    }

    private void insertSource(String sourceKey, String datasetKey, Integer sourceSrid, Integer canonicalSrid) {
        jdbcTemplate.update("""
            INSERT INTO geospatial_source
                (source_key, provider, dataset_key, display_name, source_kind,
                 expected_source_srid, canonical_srid)
            VALUES (?, 'MAA_JA_RUUMIAMET', ?, ?, 'SNAPSHOT', ?, ?)
            """, sourceKey, datasetKey, datasetKey, sourceSrid, canonicalSrid);
    }

    private String status(UUID generationId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM geospatial_generation WHERE id = ?",
            String.class,
            generationId);
    }

    private String runStatus(UUID runId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM geospatial_source_run WHERE id = ?",
            String.class,
            runId);
    }

    private List<String> eventTypes(UUID generationId) {
        return jdbcTemplate.queryForList("""
            SELECT event_type
              FROM geospatial_generation_event
             WHERE generation_id = ?
             ORDER BY occurred_at, id
            """, String.class, generationId);
    }
}
