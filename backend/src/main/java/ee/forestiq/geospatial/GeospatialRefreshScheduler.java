package ee.forestiq.geospatial;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Small scheduler scanner. Heavy provider work runs on at most two workers and every source is
 * still protected by the database advisory lock in {@link GeospatialRefreshCoordinator}.
 */
@Component
@ConditionalOnProperty(prefix = "forestiq.geospatial.scheduler", name = "enabled", havingValue = "true")
public class GeospatialRefreshScheduler {
    private final DSLContext dsl;
    private final GeospatialRefreshCoordinator coordinator;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());

    public GeospatialRefreshScheduler(DSLContext dsl, GeospatialRefreshCoordinator coordinator) {
        this.dsl = dsl;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${forestiq.geospatial.scheduler.scan-interval-ms:60000}")
    public void scan() {
        List<String> due = dsl.fetch("""
            SELECT s.source_key
              FROM geospatial_source s
             WHERE s.enabled = TRUE
               AND s.refresh_interval_seconds IS NOT NULL
               AND s.source_kind IN ('SNAPSHOT', 'WFS')
               AND NOT EXISTS (
                    SELECT 1 FROM geospatial_source_run active
                     WHERE active.source_key = s.source_key
                       AND active.status IN ('QUEUED', 'RUNNING')
               )
               AND COALESCE((
                    SELECT max(r.completed_at)
                      FROM geospatial_source_run r
                     WHERE r.source_key = s.source_key
                       AND r.status = 'SUCCESS'
               ), to_timestamp(0)) + (s.refresh_interval_seconds * interval '1 second') <= CURRENT_TIMESTAMP
             ORDER BY s.source_key
             LIMIT 4
            """).getValues("source_key", String.class);

        due.stream()
            .filter(coordinator::supports)
            .forEach(sourceKey -> workers.submit(() -> {
                try {
                    coordinator.refresh(sourceKey, "SCHEDULED", "system:geospatial-scheduler", false);
                } catch (Exception ignored) {
                    // Run/generation failure is persisted by the refresh path and exposed by admin status/metrics.
                }
            }));
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
    }
}
