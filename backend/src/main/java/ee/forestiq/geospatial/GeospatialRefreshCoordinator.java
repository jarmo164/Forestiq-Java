package ee.forestiq.geospatial;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import ee.forestiq.geospatial.cadastre.CadastreGenerationImportService;
import ee.forestiq.geospatial.cadastre.CadastreSnapshotCheckService;
import ee.forestiq.geospatial.etak.EtakAdminGenerationIngestionService;
import ee.forestiq.geospatial.etak.EtakAdminLayerManifestService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/** Cross-replica serialization and one execution path for scheduled/manual geospatial refreshes. */
@Service
public class GeospatialRefreshCoordinator {
    public static final String CADASTRE_SOURCE = "MARU_CADASTRE";

    private final DataSource dataSource;
    private final EtakAdminLayerManifestService manifest;
    private final EtakAdminGenerationIngestionService etakIngestion;
    private final CadastreSnapshotCheckService cadastreCheck;
    private final CadastreGenerationImportService cadastreImport;
    private final MeterRegistry meters;

    public GeospatialRefreshCoordinator(
        DataSource dataSource,
        EtakAdminLayerManifestService manifest,
        EtakAdminGenerationIngestionService etakIngestion,
        CadastreSnapshotCheckService cadastreCheck,
        CadastreGenerationImportService cadastreImport,
        MeterRegistry meters
    ) {
        this.dataSource = dataSource;
        this.manifest = manifest;
        this.etakIngestion = etakIngestion;
        this.cadastreCheck = cadastreCheck;
        this.cadastreImport = cadastreImport;
        this.meters = meters;
    }

    public boolean supports(String sourceKey) {
        return CADASTRE_SOURCE.equals(sourceKey)
            || (manifest.contains(sourceKey) && manifest.require(sourceKey).enabled());
    }

    public RefreshResult refresh(String sourceKey,
                                 String triggerType,
                                 String actorSubject,
                                 boolean forceSchemaDrift) throws IOException, InterruptedException {
        if (!supports(sourceKey)) {
            throw new IllegalArgumentException("Source does not support generation refresh: " + sourceKey);
        }
        Timer.Sample sample = Timer.start(meters);
        String outcome = "FAILED";
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection, sourceKey)) {
                outcome = "LOCKED";
                meters.counter("forestiq.geospatial.refresh.lock_skipped", "source", sourceKey).increment();
                return new RefreshResult(sourceKey, "LOCKED", null, null, 0);
            }
            try {
                RefreshResult result = executeRefresh(sourceKey, triggerType, actorSubject, forceSchemaDrift);
                outcome = result.outcome();
                return result;
            } finally {
                unlock(lockConnection, sourceKey);
            }
        } catch (SQLException ex) {
            throw new IOException("Unable to acquire geospatial database lock", ex);
        } finally {
            sample.stop(Timer.builder("forestiq.geospatial.refresh.duration")
                .tag("source", sourceKey)
                .tag("trigger", lowCardinalityTrigger(triggerType))
                .tag("outcome", outcome)
                .register(meters));
            meters.counter("forestiq.geospatial.refresh.requests",
                "source", sourceKey,
                "trigger", lowCardinalityTrigger(triggerType),
                "outcome", outcome).increment();
        }
    }

    private RefreshResult executeRefresh(String sourceKey,
                                         String triggerType,
                                         String actorSubject,
                                         boolean forceSchemaDrift) throws IOException, InterruptedException {
        if (CADASTRE_SOURCE.equals(sourceKey)) {
            CadastreSnapshotCheckService.SnapshotCheckResult prepared = cadastreCheck.prepareImport(triggerType);
            CadastreGenerationImportService.ImportResult result = cadastreImport.importAndPublish(prepared, actorSubject);
            return new RefreshResult(
                sourceKey,
                prepared.changed() ? "PUBLISHED" : "UNCHANGED",
                result.runId(),
                result.generationId(),
                result.rows());
        }
        EtakAdminGenerationIngestionService.IngestionResult result = etakIngestion.refresh(
            sourceKey, triggerType, actorSubject, forceSchemaDrift);
        return new RefreshResult(sourceKey, result.outcome(), result.runId(), result.generationId(), result.rowCount());
    }

    private static boolean tryLock(Connection connection, String sourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "forestiq.geospatial.refresh:" + sourceKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, String sourceKey) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, "forestiq.geospatial.refresh:" + sourceKey);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the dedicated lock connection also releases a session advisory lock.
        }
    }

    private static String lowCardinalityTrigger(String triggerType) {
        if (triggerType == null) return "UNKNOWN";
        return switch (triggerType) {
            case "SCHEDULED", "MANUAL", "RECOVERY", "BOOTSTRAP" -> triggerType;
            default -> "OTHER";
        };
    }

    public record RefreshResult(String sourceKey, String outcome, java.util.UUID runId,
                                java.util.UUID generationId, long rowCount) { }
}
