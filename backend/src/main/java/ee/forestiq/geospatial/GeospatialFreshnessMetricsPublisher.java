package ee.forestiq.geospatial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Provider/source level metrics only: never emits cadastral codes, owner ids or feature ids as tags. */
@Component
public class GeospatialFreshnessMetricsPublisher {
    private final DSLContext dsl;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> age = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stale = new ConcurrentHashMap<>();

    public GeospatialFreshnessMetricsPublisher(DSLContext dsl, MeterRegistry meters) {
        this.dsl = dsl;
        this.meters = meters;
    }

    @PostConstruct
    void register() {
        dsl.fetch("SELECT source_key FROM geospatial_source WHERE provider = 'MAA_JA_RUUMIAMET' ORDER BY source_key")
            .getValues("source_key", String.class)
            .forEach(this::registerSource);
        refresh();
    }

    @Scheduled(fixedDelayString = "${forestiq.geospatial.metrics-refresh-ms:60000}")
    public void refresh() {
        for (Record record : dsl.fetch("""
            SELECT s.source_key,
                   s.freshness_target_seconds,
                   EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - max(r.completed_at)))::bigint AS age_seconds
              FROM geospatial_source s
              LEFT JOIN geospatial_source_run r
                ON r.source_key = s.source_key AND r.status = 'SUCCESS'
             WHERE s.provider = 'MAA_JA_RUUMIAMET'
             GROUP BY s.source_key, s.freshness_target_seconds
            """)) {
            String sourceKey = record.get("source_key", String.class);
            registerSource(sourceKey);
            Number ageValue = record.get("age_seconds", Number.class);
            Number target = record.get("freshness_target_seconds", Number.class);
            long seconds = ageValue == null ? -1 : Math.max(0, ageValue.longValue());
            age.get(sourceKey).set(seconds);
            stale.get(sourceKey).set(seconds < 0 || target == null ? 0 : seconds > target.longValue() ? 1 : 0);
        }
    }

    private void registerSource(String sourceKey) {
        age.computeIfAbsent(sourceKey, key -> {
            AtomicLong value = new AtomicLong(-1);
            Gauge.builder("forestiq.geospatial.source.freshness.seconds", value, AtomicLong::get)
                .description("Age of latest successful geospatial refresh")
                .tag("source", key)
                .register(meters);
            return value;
        });
        stale.computeIfAbsent(sourceKey, key -> {
            AtomicLong value = new AtomicLong(0);
            Gauge.builder("forestiq.geospatial.source.stale", value, AtomicLong::get)
                .description("1 when source age exceeds configured freshness target")
                .tag("source", key)
                .register(meters);
            return value;
        });
    }
}
