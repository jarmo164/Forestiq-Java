package ee.forestiq.owner;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import ee.forestiq.owner.OwnerDtos.OwnerStatusDefinition;

/**
 * Small organization-scoped cache for owner status reference metadata.
 *
 * PostgreSQL is always the source of truth. Cache failures are deliberately
 * swallowed after being measured so Redis/Valkey cannot become a correctness
 * dependency. Values are stored as JSON strings to avoid provider-specific Java
 * serialization requirements.
 */
@Component
class OwnerStatusDefinitionCache {
    static final String CACHE_NAME = "owner-status-definitions";
    private static final String KEY_PREFIX = "forestiq:v1:owner-status-definitions:org:";
    private static final TypeReference<List<OwnerStatusDefinition>> VALUE_TYPE = new TypeReference<>() { };
    private static final Logger log = LoggerFactory.getLogger(OwnerStatusDefinitionCache.class);

    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;
    private final Counter errors;
    private final Counter evictions;
    private final Timer loadTimer;

    OwnerStatusDefinitionCache(ObjectProvider<CacheManager> cacheManagers, ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.cacheManager = cacheManagers.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.hits = counter("forestiq.cache.requests", "result", "hit");
        this.misses = counter("forestiq.cache.requests", "result", "miss");
        this.bypasses = counter("forestiq.cache.requests", "result", "bypass");
        this.errors = counter("forestiq.cache.errors", "operation", "access");
        this.evictions = counter("forestiq.cache.evictions", "operation", "invalidate");
        this.loadTimer = Timer.builder("forestiq.cache.load")
            .description("PostgreSQL fallback/load duration for a named application cache")
            .tag("cache", CACHE_NAME)
            .register(meterRegistry);
    }

    List<OwnerStatusDefinition> get(UUID organizationId, Supplier<List<OwnerStatusDefinition>> loader) {
        String key = key(organizationId);
        Cache cache = cache();
        if (cache == null) {
            bypasses.increment();
            return load(loader);
        }

        try {
            String json = cache.get(key, String.class);
            if (json != null) {
                try {
                    List<OwnerStatusDefinition> value = objectMapper.readValue(json, VALUE_TYPE);
                    hits.increment();
                    return value == null ? List.of() : List.copyOf(value);
                } catch (Exception malformed) {
                    errors.increment();
                    safeEvict(cache, key);
                    log.warn("Discarded malformed {} cache entry", CACHE_NAME);
                }
            } else {
                misses.increment();
            }
        } catch (RuntimeException cacheFailure) {
            errors.increment();
            log.warn("{} cache read failed; falling back to PostgreSQL", CACHE_NAME, cacheFailure);
        }

        List<OwnerStatusDefinition> loaded = load(loader);
        try {
            cache.put(key, objectMapper.writeValueAsString(loaded));
        } catch (Exception cacheFailure) {
            errors.increment();
            log.warn("{} cache write failed; returning PostgreSQL result", CACHE_NAME, cacheFailure);
        }
        return loaded;
    }

    void invalidate(UUID organizationId) {
        Cache cache = cache();
        if (cache == null) return;
        try {
            if (cache.evictIfPresent(key(organizationId))) evictions.increment();
        } catch (RuntimeException cacheFailure) {
            errors.increment();
            log.warn("{} cache invalidation failed; TTL/fallback preserves correctness", CACHE_NAME, cacheFailure);
        }
    }

    private List<OwnerStatusDefinition> load(Supplier<List<OwnerStatusDefinition>> loader) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<OwnerStatusDefinition> loaded = loader.get();
            return loaded == null ? List.of() : List.copyOf(loaded);
        } finally {
            sample.stop(loadTimer);
        }
    }

    private void safeEvict(Cache cache, String key) {
        try {
            if (cache.evictIfPresent(key)) evictions.increment();
        } catch (RuntimeException ignored) {
            errors.increment();
        }
    }

    private Cache cache() {
        return cacheManager == null ? null : cacheManager.getCache(CACHE_NAME);
    }

    private Counter counter(String name, String tagName, String tagValue) {
        return Counter.builder(name)
            .tag("cache", CACHE_NAME)
            .tag(tagName, tagValue)
            .register(meterRegistry);
    }

    static String key(UUID organizationId) {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required for protected cache keys");
        return KEY_PREFIX + organizationId;
    }
}
