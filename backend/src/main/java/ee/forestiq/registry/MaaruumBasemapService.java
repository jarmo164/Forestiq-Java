package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/**
 * Controlled proxy for Maa- ja Ruumiamet pre-tiled basemaps.
 *
 * Browser coordinates are normal XYZ (top-left origin). The official TMS service uses a
 * bottom-left Y origin, so Y is flipped exactly once at this boundary. Provider URLs stay
 * server-side and only the approved Web Mercator tilesets are exposed.
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.REAL_TIME_DOMAIN_OPERATION,
    reason = "Basemap requests proxy map data for interactive API use, not batch ETL ingestion."
)
@Service
public class MaaruumBasemapService {
    private static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_CACHE_WEIGHT_BYTES = 96L * 1024 * 1024;
    private static final int MAX_CONCURRENT_UPSTREAM_REQUESTS = 8;
    private static final int MAX_WORKERS = 4;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final Map<String, BasemapDefinition> DEFINITIONS = Map.of(
        "kaart", new BasemapDefinition("kaart", "Eesti kaart", "kaart@GMC", "png", 0, 19, "MAP"),
        "ortofoto", new BasemapDefinition("ortofoto", "Ortofoto", "foto@GMC", "jpeg", 0, 19, "ORTHO"),
        "hybriid", new BasemapDefinition("hybriid", "Hübriidkaart", "hybriid@GMC", "png", 0, 19, "HYBRID"),
        "reljeef", new BasemapDefinition("reljeef", "Reljeef", "reljeef@GMC", "png", 0, 19, "RELIEF")
    );

    private final RegistryProperties properties;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Semaphore upstreamSlots = new Semaphore(MAX_CONCURRENT_UPSTREAM_REQUESTS);
    private final Cache<String, BasemapTile> cache;
    private final MeterRegistry meters;
    private final Timer upstreamLatency;

    @Autowired
    MaaruumBasemapService(RegistryProperties properties, MeterRegistry meters) {
        this(properties, meters, null);
    }

    MaaruumBasemapService(RegistryProperties properties, MeterRegistry meters, HttpClient suppliedHttpClient) {
        this.properties = properties;
        this.meters = meters;
        if (suppliedHttpClient == null) {
            int connectSeconds = Math.max(1, Math.min(properties.getMaaamet().getConnectTimeoutSeconds(), 30));
            this.executor = Executors.newFixedThreadPool(MAX_WORKERS, task -> {
                Thread thread = new Thread(task, "maaruum-basemap-" + THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            this.httpClient = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        } else {
            this.executor = null;
            this.httpClient = suppliedHttpClient;
        }
        this.cache = Caffeine.<String, BasemapTile>newBuilder()
            .maximumWeight(MAX_CACHE_WEIGHT_BYTES)
            .weigher((String ignored, BasemapTile tile) -> Math.max(1, tile.body().length))
            .expireAfterWrite(Duration.ofHours(6))
            .build();
        this.upstreamLatency = Timer.builder("forestiq.maaruum.basemap.upstream.latency")
            .description("Maa- ja Ruumiamet basemap upstream latency")
            .register(meters);
        meters.gauge("forestiq.maaruum.basemap.cache.entries", cache, Cache::estimatedSize);
    }

    List<BasemapDescriptor> descriptors() {
        return DEFINITIONS.values().stream()
            .filter(this::enabled)
            .sorted((left, right) -> Integer.compare(order(left.key()), order(right.key())))
            .map(definition -> new BasemapDescriptor(
                definition.key(), definition.title(), definition.minZoom(), definition.maxZoom(),
                "Maa- ja Ruumiamet", sourceDate(definition), "PROVIDER_MANAGED",
                "/api/v1/map/basemaps/" + definition.key() + "/{z}/{x}/{y}",
                "kaart".equals(definition.key())))
            .toList();
    }

    BasemapTile tile(String key, int z, int x, int y) {
        BasemapDefinition definition = requireDefinition(key);
        validateTile(z, x, y, definition);
        String cacheKey = key + '|' + z + '|' + x + '|' + y;
        BasemapTile cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            meters.counter("forestiq.maaruum.basemap.cache.requests", "layer", key, "result", "hit").increment();
            return cached;
        }
        meters.counter("forestiq.maaruum.basemap.cache.requests", "layer", key, "result", "miss").increment();
        BasemapTile loaded = fetch(definition, z, x, y);
        cache.put(cacheKey, loaded);
        return loaded;
    }

    long estimatedCacheSize() { return cache.estimatedSize(); }

    URI upstreamUri(String key, int z, int x, int y) {
        BasemapDefinition definition = requireDefinition(key);
        validateTile(z, x, y, definition);
        long tmsY = (1L << z) - 1L - y;
        String base = properties.getMaaamet().getBasemapTmsBaseUrl().replaceAll("/+$", "");
        return URI.create(base + "/" + definition.tileset() + "/" + z + "/" + x + "/" + tmsY + "." + definition.extension());
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    private BasemapTile fetch(BasemapDefinition definition, int z, int x, int y) {
        boolean acquired;
        try {
            acquired = upstreamSlots.tryAcquire(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BasemapUnavailableException("Basemap request was interrupted", exception);
        }
        if (!acquired) {
            meters.counter("forestiq.maaruum.basemap.upstream.errors", "layer", definition.key(), "reason", "concurrency").increment();
            throw new BasemapUnavailableException("Basemap provider concurrency limit reached");
        }
        try {
            return fetchWithRetry(definition, upstreamUri(definition.key(), z, x, y));
        } finally {
            upstreamSlots.release();
        }
    }

    private BasemapTile fetchWithRetry(BasemapDefinition definition, URI uri) {
        int maxAttempts = Math.max(1, Math.min(properties.getMaaamet().getMaxAttempts(), 3));
        BasemapUnavailableException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png,image/jpeg")
                    .header("User-Agent", safeUserAgent())
                    .GET()
                    .build();
                HttpResponse<InputStream> response = upstreamLatency.recordCallable(
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()));
                if (response == null) throw new BasemapUnavailableException("Basemap provider returned no response");
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    closeBody(response);
                    last = new BasemapUnavailableException("Basemap provider returned HTTP " + status, status);
                    meters.counter("forestiq.maaruum.basemap.upstream.errors", "layer", definition.key(), "reason", status >= 500 ? "5xx" : "4xx").increment();
                    if (status >= 500 && attempt < maxAttempts) continue;
                    throw last;
                }
                String rawContentType = response.headers().firstValue("content-type").orElse("");
                String contentType = rawContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
                    closeBody(response);
                    throw new BasemapUnavailableException("Basemap provider returned invalid content type");
                }
                InputStream stream = response.body();
                if (stream == null) throw new BasemapUnavailableException("Basemap provider returned an empty tile");
                byte[] body;
                try (stream) { body = stream.readNBytes(MAX_TILE_BYTES + 1); }
                if (body.length == 0 || body.length > MAX_TILE_BYTES) {
                    throw new BasemapUnavailableException("Basemap provider returned an invalid tile size");
                }
                meters.counter("forestiq.maaruum.basemap.upstream.requests", "layer", definition.key(), "result", "success").increment();
                meters.counter("forestiq.maaruum.basemap.upstream.bytes", "layer", definition.key()).increment(body.length);
                return new BasemapTile(body, MediaType.parseMediaType(contentType));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BasemapUnavailableException("Basemap request was interrupted", exception);
            } catch (IOException exception) {
                last = new BasemapUnavailableException("Basemap provider is unavailable", exception);
                meters.counter("forestiq.maaruum.basemap.upstream.errors", "layer", definition.key(), "reason", "io").increment();
                if (attempt == maxAttempts) throw last;
            } catch (BasemapUnavailableException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new BasemapUnavailableException("Basemap request failed", exception);
            }
        }
        throw last == null ? new BasemapUnavailableException("Basemap provider is unavailable") : last;
    }

    private BasemapDefinition requireDefinition(String key) {
        BasemapDefinition definition = DEFINITIONS.get(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (definition == null || !enabled(definition)) throw new IllegalArgumentException("Unknown or disabled basemap: " + key);
        return definition;
    }

    private boolean enabled(BasemapDefinition definition) {
        return !"reljeef".equals(definition.key()) || properties.getMaaamet().isReliefWebMercatorEnabled();
    }

    private String sourceDate(BasemapDefinition definition) {
        return switch (definition.sourceDateKind()) {
            case "ORTHO" -> blankToNull(properties.getMaaamet().getOrtofotoSourceDate());
            case "HYBRID" -> blankToNull(properties.getMaaamet().getHybridSourceDate());
            case "RELIEF" -> blankToNull(properties.getMaaamet().getReliefSourceDate());
            default -> blankToNull(properties.getMaaamet().getMapSourceDate());
        };
    }

    private String safeUserAgent() {
        String institution = properties.getMaaamet().getMapInstitution();
        String system = properties.getMaaamet().getMapSystem();
        String environment = properties.getMaaamet().getMapEnvironment();
        return sanitize(institution) + "/" + sanitize(system) + " (" + sanitize(environment) + ")";
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? "ForestIQ" : value.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    private static int order(String key) {
        return switch (key) {
            case "kaart" -> 10;
            case "ortofoto" -> 20;
            case "hybriid" -> 30;
            default -> 40;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateTile(int z, int x, int y, BasemapDefinition definition) {
        if (z < definition.minZoom() || z > definition.maxZoom() || z > 22) {
            throw new IllegalArgumentException("Invalid basemap zoom");
        }
        long max = 1L << z;
        if (x < 0 || y < 0 || x >= max || y >= max) {
            throw new IllegalArgumentException("Invalid basemap tile coordinates");
        }
    }

    private static void closeBody(HttpResponse<InputStream> response) {
        if (response == null || response.body() == null) return;
        try { response.body().close(); } catch (IOException ignored) { }
    }

    record BasemapDescriptor(
        String key,
        String title,
        int minZoom,
        int maxZoom,
        String attribution,
        String sourceDate,
        String freshness,
        String tileUrlTemplate,
        boolean defaultSelected
    ) { }

    record BasemapTile(byte[] body, MediaType contentType) { }

    private record BasemapDefinition(
        String key,
        String title,
        String tileset,
        String extension,
        int minZoom,
        int maxZoom,
        String sourceDateKind
    ) { }

    static final class BasemapUnavailableException extends RuntimeException {
        private final int upstreamStatus;
        BasemapUnavailableException(String message) { this(message, 0, null); }
        BasemapUnavailableException(String message, Throwable cause) { this(message, 0, cause); }
        BasemapUnavailableException(String message, int upstreamStatus) { this(message, upstreamStatus, null); }
        private BasemapUnavailableException(String message, int upstreamStatus, Throwable cause) {
            super(message, cause);
            this.upstreamStatus = upstreamStatus;
        }
        int upstreamStatus() { return upstreamStatus; }
    }
}
