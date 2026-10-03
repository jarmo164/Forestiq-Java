package ee.forestiq.geospatial.cadastre;

import java.io.IOException;
import java.util.UUID;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import org.springframework.stereotype.Service;

/**
 * Prepares one verified cadastre artifact for the generation importer.
 *
 * <p>This class intentionally does not import or publish the artifact. If the checksum is new,
 * the returned run remains RUNNING so #240 can create and validate a staging generation under
 * the same run. If the checksum is already accepted, the source check completes successfully
 * without creating a redundant generation.</p>
 */
@Service
public class CadastreSnapshotCheckService {

    public static final String SOURCE_KEY = "MARU_CADASTRE";

    private final CadastreSnapshotDownloader downloader;
    private final GeospatialSourceGenerationRepository generationRepository;

    public CadastreSnapshotCheckService(CadastreSnapshotDownloader downloader,
                                        GeospatialSourceGenerationRepository generationRepository) {
        this.downloader = downloader;
        this.generationRepository = generationRepository;
    }

    public SnapshotCheckResult prepareImport(String triggerType) throws IOException, InterruptedException {
        UUID runId = generationRepository.startRun(SOURCE_KEY, triggerType);
        CadastreSnapshotDownloader.DownloadedSnapshot snapshot = null;
        try {
            snapshot = downloader.download();
            generationRepository.recordDownloadedArtifact(
                runId,
                snapshot.observedSourceVersion(),
                snapshot.sha256(),
                snapshot.bytes());

            if (generationRepository.hasAcceptedArtifactChecksum(SOURCE_KEY, snapshot.sha256())) {
                generationRepository.finishSuccessfulCheck(runId);
                return SnapshotCheckResult.unchanged(runId, snapshot);
            }

            return SnapshotCheckResult.changed(runId, snapshot);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                generationRepository.finishUnpublishedRun(
                    runId,
                    "FAILED",
                    GeospatialSourceGenerationRepository.RunCounters.zero(),
                    safeMessage(ex));
            } catch (RuntimeException completionFailure) {
                ex.addSuppressed(completionFailure);
            }
            if (snapshot != null && snapshot.createdNewFile()) {
                try {
                    downloader.deleteArtifact(snapshot);
                } catch (IOException cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
            }
            throw ex;
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return ex.getClass().getSimpleName() + ": " + message;
    }

    public record SnapshotCheckResult(
        UUID runId,
        boolean changed,
        CadastreSnapshotDownloader.DownloadedSnapshot snapshot
    ) {
        static SnapshotCheckResult changed(
            UUID runId,
            CadastreSnapshotDownloader.DownloadedSnapshot snapshot
        ) {
            return new SnapshotCheckResult(runId, true, snapshot);
        }

        static SnapshotCheckResult unchanged(
            UUID runId,
            CadastreSnapshotDownloader.DownloadedSnapshot snapshot
        ) {
            return new SnapshotCheckResult(runId, false, snapshot);
        }
    }
}
