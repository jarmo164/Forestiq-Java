package ee.forestiq.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Low-cardinality timing facade for the versioned critical DB-operation allowlist. */
@Component
public final class CriticalPathMetrics {
    public enum Operation {
        OWNER_SEARCH("owner_search"),
        PARCEL_DETAIL("parcel_detail"),
        PROTECTED_MVT("protected_mvt"),
        DURABLE_QUEUE("durable_queue"),
        STALE_REGISTRY("stale_registry");

        private final String value;
        Operation(String value) { this.value = value; }
        public String value() { return value; }
    }

    private final Map<Operation, Timer> timers = new EnumMap<>(Operation.class);

    public CriticalPathMetrics(MeterRegistry registry) {
        for (Operation operation : Operation.values()) {
            timers.put(operation, Timer.builder("forestiq.db.operation")
                .description("Latency of a versioned ForestIQ critical database operation")
                .tag("operation", operation.value())
                .publishPercentileHistogram()
                .register(registry));
        }
    }

    public <T> T record(Operation operation, Supplier<T> work) {
        return timers.get(operation).record(work);
    }

    public void record(Operation operation, Runnable work) {
        timers.get(operation).record(work);
    }
}
