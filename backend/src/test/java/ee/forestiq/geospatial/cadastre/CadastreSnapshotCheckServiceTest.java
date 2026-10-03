package ee.forestiq.geospatial.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CadastreSnapshotCheckServiceTest {

    private CadastreSnapshotDownloader downloader;
    private GeospatialSourceGenerationRepository repository;
    private CadastreSnapshotCheckService service;

    @BeforeEach
    void setUp() {
        downloader = mock(CadastreSnapshotDownloader.class);
        repository = mock(GeospatialSourceGenerationRepository.class);
        service = new CadastreSnapshotCheckService(downloader, repository);
    }

    @Test
    void leavesNewVerifiedArtifactRunOpenForImporter() throws Exception {
        UUID runId = UUID.randomUUID();
        var snapshot = snapshot("abc123", true);
        when(repository.startRun(CadastreSnapshotCheckService.SOURCE_KEY, "SCHEDULED")).thenReturn(runId);
        when(downloader.download()).thenReturn(snapshot);
        when(repository.hasAcceptedArtifactChecksum(CadastreSnapshotCheckService.SOURCE_KEY, "abc123"))
            .thenReturn(false);

        var result = service.prepareImport("SCHEDULED");

        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.changed()).isTrue();
        assertThat(result.snapshot()).isSameAs(snapshot);
        verify(repository).recordDownloadedArtifact(runId, "\"v1\"", "abc123", 123L);
        verify(repository, never()).finishSuccessfulCheck(runId);
        verify(repository, never()).finishUnpublishedRun(
            runId,
            "FAILED",
            GeospatialSourceGenerationRepository.RunCounters.zero(),
            null);
    }

    @Test
    void completesUnchangedChecksumAsSuccessfulSourceCheck() throws Exception {
        UUID runId = UUID.randomUUID();
        var snapshot = snapshot("same", false);
        when(repository.startRun(CadastreSnapshotCheckService.SOURCE_KEY, "MANUAL")).thenReturn(runId);
        when(downloader.download()).thenReturn(snapshot);
        when(repository.hasAcceptedArtifactChecksum(CadastreSnapshotCheckService.SOURCE_KEY, "same"))
            .thenReturn(true);

        var result = service.prepareImport("MANUAL");

        assertThat(result.changed()).isFalse();
        verify(repository).recordDownloadedArtifact(runId, "\"v1\"", "same", 123L);
        verify(repository).finishSuccessfulCheck(runId);
        verify(downloader, never()).deleteArtifact(snapshot);
    }

    @Test
    void recordsDownloadFailureWithoutCreatingGeneration() throws Exception {
        UUID runId = UUID.randomUUID();
        when(repository.startRun(CadastreSnapshotCheckService.SOURCE_KEY, "SCHEDULED")).thenReturn(runId);
        doThrow(new IOException("provider timeout")).when(downloader).download();

        assertThatThrownBy(() -> service.prepareImport("SCHEDULED"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("provider timeout");

        verify(repository).finishUnpublishedRun(
            runId,
            "FAILED",
            GeospatialSourceGenerationRepository.RunCounters.zero(),
            "IOException: provider timeout");
    }

    private static CadastreSnapshotDownloader.DownloadedSnapshot snapshot(
        String checksum,
        boolean createdNewFile
    ) {
        Instant now = Instant.parse("2026-09-06T06:00:00Z");
        return new CadastreSnapshotDownloader.DownloadedSnapshot(
            Path.of("/tmp/cadastre-test.zip"),
            URI.create("https://example.test/cadastre.zip"),
            checksum,
            123L,
            Optional.of(123L),
            Optional.of("\"v1\""),
            Optional.of("Sun, 06 Sep 2026 01:00:00 GMT"),
            Optional.of("application/zip"),
            createdNewFile,
            now,
            now.plusSeconds(1)
        );
    }
}
