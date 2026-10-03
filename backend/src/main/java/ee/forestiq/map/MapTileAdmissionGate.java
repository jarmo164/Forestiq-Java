package ee.forestiq.map;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded admission for local PostGIS tile misses.
 *
 * <p>Callers must resolve authorization/generation/cache identity before entering
 * this boundary. The gate uses a finite family enum so metrics cannot gain
 * XYZ, tenant, subject, cadastral-code or query cardinality.</p>
 */
@Component
public final class MapTileAdmissionGate {
    public enum Family {
        CADASTRE("cadastre"),
        FORESTRY("forestry"),
        OVERLAY("overlay");

        private final String metricValue;
        Family(String metricValue) { this.metricValue = metricValue; }
        public String metricValue() { return metricValue; }
    }

    private final MeterRegistry meterRegistry;
    private final Map<Family, Semaphore> permits = new EnumMap<>(Family.class);
    private final Map<Family, AtomicInteger> inFlight = new EnumMap<>(Family.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final long deadlineMillis;

    @Autowired
    public MapTileAdmissionGate(
        MeterRegistry meterRegistry,
        @Value("${forestiq.map.tile.admission.max-in-flight:8}") int maxInFlight,
        @Value("${forestiq.map.tile.admission.deadline-ms:300}") long deadlineMillis
    ) {
        if (maxInFlight < 1) throw new IllegalArgumentException("map tile max-in-flight must be positive");
        if (deadlineMillis < 1) throw new IllegalArgumentException("map tile deadline must be positive");
        this.meterRegistry = meterRegistry;
        this.deadlineMillis = deadlineMillis;
        for (Family family : Family.values()) {
            permits.put(family, new Semaphore(maxInFlight, true));
            AtomicInteger gauge = new AtomicInteger();
            inFlight.put(family, gauge);
            meterRegistry.gauge("forestiq.map.tile.inflight",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("family", family.metricValue())),
                gauge);
        }
    }

    /** Focused-test constructor with production defaults. */
    public MapTileAdmissionGate(MeterRegistry meterRegistry) {
        this(meterRegistry, 8, 300L);
    }

    public <T> T execute(Family family, Supplier<T> work) {
        Semaphore semaphore = permits.get(family);
        if (!semaphore.tryAcquire()) {
            admission(family, "overload");
            meterRegistry.counter("forestiq.map.tile.overload", "family", family.metricValue()).increment();
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Map tile capacity is busy; retry shortly");
        }

        AtomicInteger active = inFlight.get(family);
        active.incrementAndGet();
        admission(family, "admitted");
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean released = new AtomicBoolean(false);
        Future<T> future;
        try {
            future = executor.submit(() -> {
                started.set(true);
                try {
                    return work.get();
                } finally {
                    release(active, semaphore, released);
                }
            });
        } catch (RuntimeException ex) {
            release(active, semaphore, released);
            throw ex;
        }

        try {
            T value = future.get(deadlineMillis, TimeUnit.MILLISECONDS);
            admission(family, "completed");
            return value;
        } catch (TimeoutException ex) {
            boolean cancelled = future.cancel(true);
            if (cancelled && !started.get()) {
                release(active, semaphore, released);
            }
            admission(family, "deadline");
            meterRegistry.counter("forestiq.map.tile.deadline", "family", family.metricValue()).increment();
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Map tile generation deadline exceeded; retry shortly",
                ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            admission(family, "interrupted");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Map tile generation interrupted", ex);
        } catch (ExecutionException ex) {
            admission(family, "failed");
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Map tile generation failed", cause);
        }
    }

    int availablePermits(Family family) {
        return permits.get(family).availablePermits();
    }

    private void admission(Family family, String outcome) {
        meterRegistry.counter("forestiq.map.tile.admission",
            "family", family.metricValue(),
            "outcome", outcome).increment();
    }

    private static void release(AtomicInteger active, Semaphore semaphore, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            active.decrementAndGet();
            semaphore.release();
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
