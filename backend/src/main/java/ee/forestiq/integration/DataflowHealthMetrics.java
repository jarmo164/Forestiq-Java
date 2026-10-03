package ee.forestiq.integration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality operational gauges for production alerting. Metrics are
 * intentionally aggregated to the worst organization for each bounded job key;
 * tenant identifiers are not emitted as Prometheus labels.
 */
@Component
class DataflowHealthMetrics {
    private static final Logger log = LoggerFactory.getLogger(DataflowHealthMetrics.class);

    private final IntegrationRunRepository runs;
    private final Map<IntegrationJobKey, IntegrationJobHandler> handlers;
    private final EnumMap<IntegrationJobKey, AtomicLong> enabled = values(-1);
    private final EnumMap<IntegrationJobKey, AtomicLong> stale = values(-1);
    private final EnumMap<IntegrationJobKey, AtomicLong> latestSuccessAge = values(-1);
    private final EnumMap<IntegrationJobKey, AtomicLong> failureStreak = values(0);
    private final EnumMap<IntegrationJobKey, AtomicLong> backlog = values(0);
    private final EnumMap<IntegrationJobKey, AtomicLong> cursorLag = values(-1);

    DataflowHealthMetrics(IntegrationRunRepository runs, List<IntegrationJobHandler> handlers,
                          MeterRegistry meterRegistry) {
        this.runs = runs;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
            IntegrationJobHandler::key, handler -> handler));
        for (IntegrationJobKey key : IntegrationJobKey.values()) {
            register(meterRegistry, "forestiq.dataflow.enabled", key, enabled.get(key), AtomicLong::doubleValue,
                "1 when the production feed is configured and automatic scheduling is enabled");
            register(meterRegistry, "forestiq.dataflow.stale", key, stale.get(key), AtomicLong::doubleValue,
                "1 when an enabled feed exceeds its freshness SLO or has no confirmed successful run");
            register(meterRegistry, "forestiq.dataflow.latest.success.age", key, latestSuccessAge.get(key), AtomicLong::doubleValue,
                "Worst latest-success age across organizations; -1 means no successful run has been observed", "seconds");
            register(meterRegistry, "forestiq.dataflow.failure.streak", key, failureStreak.get(key), AtomicLong::doubleValue,
                "Worst consecutive FAILED/PARTIAL run streak since the last success");
            register(meterRegistry, "forestiq.dataflow.backlog", key, backlog.get(key), AtomicLong::doubleValue,
                "1 when an integration checkpoint or run metric reports unfinished catch-up/backlog");
            register(meterRegistry, "forestiq.dataflow.cursor.lag", key, cursorLag.get(key), AtomicLong::doubleValue,
                "Worst cursor lag for cursor-based feeds; -1 when the feed has no cursor", "seconds");
        }
    }

    @Scheduled(
        fixedDelayString = "${forestiq.dataflow.health.metrics-refresh-ms:30000}",
        initialDelayString = "${forestiq.dataflow.health.metrics-initial-delay-ms:10000}"
    )
    void refresh() {
        for (IntegrationJobKey key : IntegrationJobKey.values()) {
            try {
                IntegrationJobHandler handler = handlers.get(key);
                boolean feedEnabled = handler != null && handler.enabled() && handler.configured();
                Long successAge = runs.worstLatestSuccessAgeSeconds(key);
                Long lag = runs.worstCursorLagSeconds(key);
                long slo = IntegrationJobHealthService.freshnessSlo(key).toSeconds();
                boolean feedStale = feedEnabled && (successAge == null || successAge > slo || (lag != null && lag > slo));

                enabled.get(key).set(feedEnabled ? 1 : 0);
                stale.get(key).set(feedStale ? 1 : 0);
                latestSuccessAge.get(key).set(successAge == null ? -1 : successAge);
                failureStreak.get(key).set(runs.worstFailureStreak(key));
                backlog.get(key).set(runs.anyBacklog(key) ? 1 : 0);
                cursorLag.get(key).set(lag == null ? -1 : lag);
            } catch (RuntimeException exception) {
                stale.get(key).set(1);
                log.warn("Unable to refresh dataflow health metrics for {}", key, exception);
            }
        }
    }

    private static EnumMap<IntegrationJobKey, AtomicLong> values(long initial) {
        EnumMap<IntegrationJobKey, AtomicLong> result = new EnumMap<>(IntegrationJobKey.class);
        for (IntegrationJobKey key : IntegrationJobKey.values()) result.put(key, new AtomicLong(initial));
        return result;
    }

    private static void register(MeterRegistry registry, String name, IntegrationJobKey key,
                                 AtomicLong value, ToDoubleFunction<AtomicLong> function,
                                 String description) {
        Gauge.builder(name, value, function)
            .tag("job", key.name())
            .description(description)
            .register(registry);
    }

    private static void register(MeterRegistry registry, String name, IntegrationJobKey key,
                                 AtomicLong value, ToDoubleFunction<AtomicLong> function,
                                 String description, String baseUnit) {
        Gauge.builder(name, value, function)
            .tag("job", key.name())
            .description(description)
            .baseUnit(baseUnit)
            .register(registry);
    }
}
