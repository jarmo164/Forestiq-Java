package ee.forestiq.registry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Separates locally usable generation freshness from external map-provider availability. */
@Service
public class MetsaregisterLayerHealthService {
    private static final String FORESTRY_SOURCE = "METSAREGISTER_FORESTRY";

    private final DSLContext dsl;
    private final Map<String, ExternalHealth> external = new ConcurrentHashMap<>();

    @Autowired
    public MetsaregisterLayerHealthService(DSLContext dsl) {
        this.dsl = dsl;
    }

    MetsaregisterLayerHealthService() {
        this.dsl = null;
    }

    public void recordExternalSuccess(String layerKey) {
        external.put(layerKey, new ExternalHealth("AVAILABLE", Instant.now(), null));
    }

    public void recordExternalFailure(String layerKey, String errorCode) {
        external.put(layerKey, new ExternalHealth("UNAVAILABLE", Instant.now(), safe(errorCode)));
    }

    public ExternalHealth externalHealth(String layerKey) {
        return external.getOrDefault(layerKey, new ExternalHealth("UNKNOWN", null, null));
    }

    public LocalHealth forestryLocalHealth(String freshnessTarget) {
        if (dsl == null) return new LocalHealth("UNKNOWN", null, null, null);
        var row = dsl.fetchOne("""
            SELECT g.source_date, g.published_at
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ?
            """, FORESTRY_SOURCE);
        if (row == null) return new LocalHealth("UNAVAILABLE", null, null, null);

        LocalDate sourceDate = row.get("source_date", LocalDate.class);
        OffsetDateTime publishedAt = row.get("published_at", OffsetDateTime.class);
        Duration target = parseTarget(freshnessTarget);
        String freshness = "FRESH";
        if (publishedAt != null && target != null
            && publishedAt.toInstant().plus(target).isBefore(Instant.now())) {
            freshness = "STALE_USABLE";
        }
        return new LocalHealth("AVAILABLE", freshness, sourceDate, publishedAt);
    }

    static Duration parseTarget(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("h")) return Duration.ofHours(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            if (normalized.endsWith("d")) return Duration.ofDays(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            if (normalized.endsWith("m")) return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            return Duration.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "UPSTREAM_ERROR";
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    public record ExternalHealth(String availability, Instant lastCheckedAt, String errorCode) { }
    public record LocalHealth(String availability, String freshness, LocalDate sourceDate, OffsetDateTime publishedAt) { }
}
