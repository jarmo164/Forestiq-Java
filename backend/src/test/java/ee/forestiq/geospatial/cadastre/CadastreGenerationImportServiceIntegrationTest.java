package ee.forestiq.geospatial.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
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
        "forestiq.scheduling.enabled=false",
        "forestiq.geospatial.cadastre.minimum-rows=1",
        "forestiq.geospatial.cadastre.maximum-row-count-drift-ratio=0.50"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("core")
class CadastreGenerationImportServiceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "tunnus", "hkood", "mk_nimi", "ov_nimi", "ay_nimi", "oiguslik_alus", "eksport");

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
    @MockBean private CadastreSnapshotArchive archive;
    @MockBean private CadastreGdalAdapter gdal;

    @Autowired private CadastreGenerationImportService importer;
    @Autowired private GeospatialSourceGenerationRepository generations;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void resetGeospatialTables() {
        dsl.execute("""
            TRUNCATE TABLE
                geospatial_cadastre_parcel,
                geospatial_generation_event,
                geospatial_source_current,
                geospatial_generation,
                geospatial_source_run,
                geospatial_source
            RESTART IDENTITY CASCADE
            """);
        dsl.execute("""
            INSERT INTO geospatial_source
                (source_key, provider, dataset_key, display_name, source_kind,
                 expected_source_srid, canonical_srid)
            VALUES ('MARU_CADASTRE', 'MAA_JA_RUUMIAMET', 'cadastre',
                    'Maa- ja Ruumiamet — kataster', 'SNAPSHOT', 3301, 3301)
            """);
    }

    @Test
    void publishesCanonicalGenerationAtomicallyAndMakesRowsImmutable() throws Exception {
        Prepared prepared = prepare("sha256:first", "etag-first");
        String stage = stageTable(prepared.runId());
        createStage(stage);
        insertStageRow(stage, "11101:001:0001", "001", LocalDate.of(2026, 9, 6), 1,
            500_000, 6_500_000, 500_100, 6_500_100, 3301);
        insertStageRow(stage, "11101:001:0002", "002", LocalDate.of(2026, 9, 6), 2,
            500_200, 6_500_000, 500_300, 6_500_100, 3301);
        stubGdal(prepared, stage, 2);

        CadastreGenerationImportService.ImportResult result =
            importer.importAndPublish(prepared.check(), "integration-test");

        assertThat(result.published()).isTrue();
        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.counters()).isEqualTo(
            new CadastreGenerationImportService.GenerationCounters(2, 0, 0, 0));
        assertThat(generations.currentGenerationId("MARU_CADASTRE")).isEqualTo(result.generationId());
        assertThat(currentCodes()).containsExactlyInAnyOrder("11101:001:0001", "11101:001:0002");
        assertThat(generationStatus(result.generationId())).isEqualTo("PUBLISHED");
        assertThat(runStatus(prepared.runId())).isEqualTo("SUCCESS");
        assertThat(tableExists(stage)).isFalse();

        assertThatThrownBy(() -> dsl.execute(
            "UPDATE geospatial_cadastre_parcel SET source_area_m2 = 1 WHERE generation_id = ?",
            result.generationId()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void computesInsertedUpdatedUnchangedAndRemovedAgainstOnlyPreviousGeneration() throws Exception {
        Prepared first = prepare("sha256:g1", "etag-g1");
        String firstStage = stageTable(first.runId());
        createStage(firstStage);
        insertStageRow(firstStage, "A", "same", LocalDate.of(2026, 9, 5), 1,
            500_000, 6_500_000, 500_050, 6_500_050, 3301);
        insertStageRow(firstStage, "B", "old", LocalDate.of(2026, 9, 5), 2,
            500_100, 6_500_000, 500_150, 6_500_050, 3301);
        insertStageRow(firstStage, "D", "removed", LocalDate.of(2026, 9, 5), 3,
            500_200, 6_500_000, 500_250, 6_500_050, 3301);
        stubGdal(first, firstStage, 3);
        var firstResult = importer.importAndPublish(first.check(), "integration-test");

        Prepared second = prepare("sha256:g2", "etag-g2");
        String secondStage = stageTable(second.runId());
        createStage(secondStage);
        // A is unchanged even though provider export date and transient ogr_fid changed.
        insertStageRow(secondStage, "A", "same", LocalDate.of(2026, 9, 6), 101,
            500_000, 6_500_000, 500_050, 6_500_050, 3301);
        // B has a source attribute change.
        insertStageRow(secondStage, "B", "new", LocalDate.of(2026, 9, 6), 102,
            500_100, 6_500_000, 500_150, 6_500_050, 3301);
        // C is new; D disappeared.
        insertStageRow(secondStage, "C", "added", LocalDate.of(2026, 9, 6), 103,
            500_300, 6_500_000, 500_350, 6_500_050, 3301);
        stubGdal(second, secondStage, 3);

        var secondResult = importer.importAndPublish(second.check(), "integration-test");

        assertThat(secondResult.counters()).isEqualTo(
            new CadastreGenerationImportService.GenerationCounters(1, 1, 1, 1));
        assertThat(currentCodes()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(generationStatus(firstResult.generationId())).isEqualTo("RETIRED");
        assertThat(generationStatus(secondResult.generationId())).isEqualTo("PUBLISHED");

        generations.rollbackToRetired(
            "MARU_CADASTRE",
            firstResult.generationId(),
            "integration-test",
            "Verify last-known-good rollback");

        assertThat(currentCodes()).containsExactlyInAnyOrder("A", "B", "D");
        assertThat(generationStatus(firstResult.generationId())).isEqualTo("PUBLISHED");
        assertThat(generationStatus(secondResult.generationId())).isEqualTo("RETIRED");
    }

    @Test
    void duplicateIdentityFailsClosedAndKeepsPreviousCurrentGeneration() throws Exception {
        Prepared first = prepare("sha256:good", "etag-good");
        String firstStage = stageTable(first.runId());
        createStage(firstStage);
        insertStageRow(firstStage, "GOOD", "001", LocalDate.of(2026, 9, 5), 1,
            500_000, 6_500_000, 500_050, 6_500_050, 3301);
        stubGdal(first, firstStage, 1);
        var firstResult = importer.importAndPublish(first.check(), "integration-test");

        Prepared bad = prepare("sha256:duplicate", "etag-duplicate");
        String badStage = stageTable(bad.runId());
        createStage(badStage);
        insertStageRow(badStage, "DUP", "001", LocalDate.of(2026, 9, 6), 10,
            500_100, 6_500_000, 500_150, 6_500_050, 3301);
        insertStageRow(badStage, "DUP", "002", LocalDate.of(2026, 9, 6), 11,
            500_200, 6_500_000, 500_250, 6_500_050, 3301);
        stubGdal(bad, badStage, 2);

        assertThatThrownBy(() -> importer.importAndPublish(bad.check(), "integration-test"))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("duplicateCodes=1");

        assertThat(generations.currentGenerationId("MARU_CADASTRE")).isEqualTo(firstResult.generationId());
        assertThat(currentCodes()).containsExactly("GOOD");
        assertThat(runStatus(bad.runId())).isEqualTo("FAILED");
        assertThat(generationCountForRun(bad.runId())).isZero();
        assertThat(tableExists(badStage)).isFalse();
    }

    @Test
    void wrongSridFailsClosedBeforeAnyGenerationCanBePublished() throws Exception {
        Prepared bad = prepare("sha256:wrong-srid", "etag-wrong-srid");
        String stage = stageTable(bad.runId());
        createStage(stage);
        insertStageRow(stage, "WRONG", "001", LocalDate.of(2026, 9, 6), 1,
            24.7, 59.4, 24.8, 59.5, 4326);
        stubGdal(bad, stage, 1);

        assertThatThrownBy(() -> importer.importAndPublish(bad.check(), "integration-test"))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("wrongSrid=1");

        assertThat(generations.currentGenerationId("MARU_CADASTRE")).isNull();
        assertThat(runStatus(bad.runId())).isEqualTo("FAILED");
        assertThat(generationCountForRun(bad.runId())).isZero();
    }

    private Prepared prepare(String checksum, String sourceVersion) {
        UUID runId = generations.startRun("MARU_CADASTRE", "MANUAL");
        CadastreSnapshotDownloader.DownloadedSnapshot snapshot =
            new CadastreSnapshotDownloader.DownloadedSnapshot(
                Path.of("/tmp/" + checksum.replace(':', '-') + ".zip"),
                URI.create("https://example.invalid/cadastre.zip"),
                checksum,
                100L,
                Optional.of(100L),
                Optional.of(sourceVersion),
                Optional.empty(),
                Optional.of("application/zip"),
                false,
                Instant.parse("2026-09-06T10:00:00Z"),
                Instant.parse("2026-09-06T10:00:01Z"));
        generations.recordDownloadedArtifact(runId, sourceVersion, checksum, 100L);
        return new Prepared(
            runId,
            snapshot,
            CadastreSnapshotCheckService.SnapshotCheckResult.changed(runId, snapshot));
    }

    private void stubGdal(Prepared prepared, String stageTable, long featureCount) throws Exception {
        Path workDir = Files.createTempDirectory("forestiq-cadastre-test-");
        Path gpkg = workDir.resolve("cadastre.gpkg");
        CadastreSnapshotArchive.ExtractedGeoPackage extracted =
            new CadastreSnapshotArchive.ExtractedGeoPackage(workDir, gpkg, 1L);
        CadastreGdalAdapter.GeoPackageManifest manifest = new CadastreGdalAdapter.GeoPackageManifest(
            "kataster",
            "geom",
            "tunnus",
            3301,
            featureCount,
            "schema-v1",
            MANIFEST_FIELDS);

        when(archive.extract(prepared.snapshot())).thenReturn(extracted);
        when(gdal.inspect(gpkg)).thenReturn(manifest);
        when(gdal.importToStage(gpkg, manifest, prepared.runId())).thenReturn(stageTable);
    }

    private void createStage(String stage) {
        dsl.execute("""
            CREATE TABLE %s (
                ogr_fid BIGINT,
                tunnus TEXT,
                hkood TEXT,
                mk_nimi TEXT,
                ov_nimi TEXT,
                ay_nimi TEXT,
                oiguslik_alus TEXT,
                eksport DATE,
                geom geometry
            )
            """.formatted(stage));
    }

    private void insertStageRow(String stage,
                                String code,
                                String hkood,
                                LocalDate exportDate,
                                long ogrFid,
                                double minX,
                                double minY,
                                double maxX,
                                double maxY,
                                int srid) {
        dsl.execute("""
            INSERT INTO %s
                (ogr_fid, tunnus, hkood, mk_nimi, ov_nimi, ay_nimi, oiguslik_alus, eksport, geom)
            VALUES (?, ?, ?, 'Harju maakond', 'Test vald', 'Test asustus', 'test', ?,
                    ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, ?)))
            """.formatted(stage),
            ogrFid, code, hkood, exportDate, minX, minY, maxX, maxY, srid);
    }

    private String stageTable(UUID runId) {
        return CadastreGdalAdapter.stageTable(runId);
    }

    private List<String> currentCodes() {
        return jdbcTemplate.queryForList(
            "SELECT cadastral_code FROM geospatial_cadastre_current ORDER BY cadastral_code",
            String.class);
    }

    private String generationStatus(UUID generationId) {
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

    private int generationCountForRun(UUID runId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM geospatial_generation WHERE run_id = ?",
            Integer.class,
            runId);
        return count == null ? 0 : count;
    }

    private boolean tableExists(String table) {
        String regclass = jdbcTemplate.queryForObject(
            "SELECT to_regclass(?)::text",
            String.class,
            table);
        return regclass != null;
    }

    private record Prepared(
        UUID runId,
        CadastreSnapshotDownloader.DownloadedSnapshot snapshot,
        CadastreSnapshotCheckService.SnapshotCheckResult check
    ) {
    }
}
