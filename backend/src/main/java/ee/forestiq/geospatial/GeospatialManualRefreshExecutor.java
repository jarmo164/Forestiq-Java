package ee.forestiq.geospatial;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/** Returns admin requests quickly while the shared refresh coordinator owns locking and run state. */
@Service
public class GeospatialManualRefreshExecutor {
    private final GeospatialRefreshCoordinator coordinator;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    public GeospatialManualRefreshExecutor(GeospatialRefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void submit(String sourceKey, String actorSubject, boolean forceSchemaDrift) {
        if (!coordinator.supports(sourceKey)) {
            throw new IllegalArgumentException("Manual refresh is not supported for source: " + sourceKey);
        }
        worker.submit(() -> {
            try {
                coordinator.refresh(sourceKey, "MANUAL", actorSubject, forceSchemaDrift);
            } catch (Exception ignored) {
                // Failure state/error summary is persisted by the run pipeline and shown in Admin → Teenused.
            }
        });
    }

    public boolean supports(String sourceKey) {
        return coordinator.supports(sourceKey);
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
