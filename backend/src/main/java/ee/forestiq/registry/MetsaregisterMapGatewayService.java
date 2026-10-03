package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.REAL_TIME_DOMAIN_OPERATION,
    reason = "Map gateway requests are interactive map-serving boundaries, not scheduled batch ETL ingestion."
)
@Service
public class MetsaregisterMapGatewayService {
    private static final Set<String> ALLOWED_FORMATS = Set.of("image/png", "image/jpeg");
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_CACHE_WEIGHT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_GATEWAY_WORKERS = 4;
    private static final int MAX_CONCURRENT_UPSTREAM_REQUESTS = 8;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final RegistryProperties properties;
    private final MetsaregisterLayerManifestService manifest;
    private final MetsaregisterSpatialContractService spatialContract;
    private final MetsaregisterLayerHealthService layerHealth;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Semaphore upstreamSlots = new Semaphore(MAX_CONCURRENT_UPSTREAM_REQUESTS);
    private final Cache<String, GatewayImage> cache;
    private final Counter requests;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter upstreamErrors;
    private final Counter upstreamBytes;
    private final Timer upstreamLatency;

    @Autowired
    MetsaregisterMapGatewayService(RegistryProperties properties,
                                   MetsaregisterLayerManifestService manifest,
                                   MeterRegistry meterRegistry,
                                   MetsaregisterSpatialContractService spatialContract,
                                   MetsaregisterLayerHealthService layerHealth) {
        this(properties, manifest, meterRegistry, null, spatialContract, layerHealth);
    }

    /** Compatibility constructor for focused unit tests with a supplied HTTP client. */
    MetsaregisterMapGatewayService(RegistryProperties properties,
                                   MetsaregisterLayerManifestService manifest,
                                   MeterRegistry meterRegistry,
                                   HttpClient suppliedHttpClient) {
        this(properties, manifest, meterRegistry, suppliedHttpClient,
            new MetsaregisterSpatialContractService(manifest, meterRegistry),
            new MetsaregisterLayerHealthService());
    }

    MetsaregisterMapGatewayService(RegistryProperties properties,
                                   MetsaregisterLayerManifestService manifest,
                                   MeterRegistry meterRegistry,
                                   HttpClient suppliedHttpClient,
                                   MetsaregisterSpatialContractService spatialContract,
                                   MetsaregisterLayerHealthService layerHealth) {
        this.properties = properties;
        this.manifest = manifest;
        this.spatialContract = spatialContract;
        this.layerHealth = layerHealth;
        if (suppliedHttpClient == null) {
            int connectSeconds = Math.max(1, Math.min(properties.getMetsaregister().getConnectTimeoutSeconds(), 30));
            this.executor = Executors.newFixedThreadPool(MAX_GATEWAY_WORKERS, task -> {
                Thread thread = new Thread(task, "metsaregister-map-" + THREAD_SEQUENCE.incrementAndGet());
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
        this.cache = Caffeine.<String, GatewayImage>newBuilder()
            .maximumWeight(MAX_CACHE_WEIGHT_BYTES)
            .weigher((String key, GatewayImage image) -> Math.max(1, image.body().length))
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
        this.requests = meterRegistry.counter("forestiq.metsaregister.map.requests");
        this.cacheHits = meterRegistry.counter("forestiq.metsaregister.map.cache.hits");
        this.cacheMisses = meterRegistry.counter("forestiq.metsaregister.map.cache.misses");
        this.upstreamErrors = meterRegistry.counter("forestiq.metsaregister.map.upstream.errors");
        this.upstreamBytes = meterRegistry.counter("forestiq.metsaregister.map.upstream.bytes");
        this.upstreamLatency = meterRegistry.timer("forestiq.metsaregister.map.upstream.latency");
    }

    GatewayImage getMap(String layerKey, String bbox, int width, int height, String crs, String format) {
        return getMap(layerKey, bbox, width, height, crs, format, null);
    }

    GatewayImage getMap(String layerKey, String bbox, int width, int height, String crs, String format, Integer zoom) {
        requests.increment();
        var layer = manifest.requireLayer(layerKey);
        if (!layer.enabled() || layer.mapPresentationMode() != MetsaregisterLayerManifestService.MapPresentationMode.WMS) {
            throw new IllegalArgumentException("Layer is not approved for WMS map presentation: " + layerKey);
        }
        var spatial = spatialContract.normalizeMapRequest(layer, crs, bbox);
        String normalizedBbox = spatial.bbox();
        String normalizedCrs = spatial.crs();
        String normalizedFormat = normalizeFormat(format);
        int normalizedWidth = validateDimension(width, "width");
        int normalizedHeight = validateDimension(height, "height");
        Integer normalizedZoom = validateZoom(zoom, layer.minZoom(), layer.maxZoom());
        String cacheKey = String.join("|", layerKey, normalizedBbox, Integer.toString(normalizedWidth), Integer.toString(normalizedHeight), normalizedCrs, normalizedFormat, normalizedZoom == null ? "-" : normalizedZoom.toString());

        GatewayImage cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        cacheMisses.increment();

        URI upstream = UriComponentsBuilder.fromUriString(properties.getMetsaregister().getWfsUrl())
            .replaceQuery(null)
            .queryParam("SERVICE", "WMS")
            .queryParam("VERSION", "1.1.1")
            .queryParam("REQUEST", "GetMap")
            .queryParam("LAYERS", layer.key())
            .queryParam("STYLES", "")
            .queryParam("SRS", normalizedCrs)
            .queryParam("BBOX", normalizedBbox)
            .queryParam("WIDTH", normalizedWidth)
            .queryParam("HEIGHT", normalizedHeight)
            .queryParam("FORMAT", normalizedFormat)
            .queryParam("TRANSPARENT", "TRUE")
            .build(true)
            .toUri();

        GatewayImage image = fetch(layerKey, upstream, normalizedFormat);
        cache.put(cacheKey, image);
        return image;
    }

    void clearCache() { cache.invalidateAll(); cache.cleanUp(); }
    long estimatedCacheSize() { return cache.estimatedSize(); }
    long estimatedCacheWeightBytes() { return cache.policy().eviction().map(eviction -> eviction.weightedSize().orElse(0L)).orElse(0L); }

    @PreDestroy
    void shutdown() { if (executor != null) executor.shutdownNow(); }

    private GatewayImage fetch(String layerKey, URI upstream, String requestedFormat) {
        boolean acquired;
        try {
            acquired = upstreamSlots.tryAcquire(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            layerHealth.recordExternalFailure(layerKey, "INTERRUPTED");
            throw new MetsaregisterMapGatewayException("Metsaregister map request was interrupted", exception);
        }
        if (!acquired) {
            upstreamErrors.increment();
            layerHealth.recordExternalFailure(layerKey, "CONCURRENCY_LIMIT");
            throw new MetsaregisterMapGatewayException("Metsaregister map gateway is at its upstream concurrency limit");
        }
        try {
            GatewayImage image = fetchWithRetry(upstream, requestedFormat);
            layerHealth.recordExternalSuccess(layerKey);
            return image;
        } catch (RuntimeException exception) {
            layerHealth.recordExternalFailure(layerKey, failureCode(exception));
            throw exception;
        } finally {
            upstreamSlots.release();
        }
    }

    private GatewayImage fetchWithRetry(URI upstream, String requestedFormat) {
        int maxAttempts = Math.max(1, Math.min(properties.getMetsaregister().getMaxAttempts(), 3));
        MetsaregisterMapGatewayException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(20)).header("Accept", requestedFormat).GET().build();
                HttpResponse<InputStream> response = upstreamLatency.recordCallable(() -> httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()));
                if (response == null) throw new IllegalStateException("Metsaregister returned no response");
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    closeBody(response);
                    upstreamErrors.increment();
                    last = new MetsaregisterMapGatewayException("Metsaregister map upstream returned HTTP " + status, status);
                    if (status >= 500 && attempt < maxAttempts) continue;
                    throw last;
                }
                String contentType = response.headers().firstValue("content-type").orElse(requestedFormat);
                String safeContentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(safeContentType)) {
                    closeBody(response);
                    upstreamErrors.increment();
                    throw new MetsaregisterMapGatewayException("Metsaregister map upstream returned non-image content", status);
                }
                InputStream stream = response.body();
                if (stream == null) {
                    upstreamErrors.increment();
                    throw new MetsaregisterMapGatewayException("Metsaregister map upstream returned an invalid image size", status);
                }
                byte[] body;
                try (stream) { body = stream.readNBytes(MAX_IMAGE_BYTES + 1); }
                if (body.length == 0 || body.length > MAX_IMAGE_BYTES) {
                    upstreamErrors.increment();
                    throw new MetsaregisterMapGatewayException("Metsaregister map upstream returned an invalid image size", status);
                }
                upstreamBytes.increment(body.length);
                return new GatewayImage(body, MediaType.parseMediaType(safeContentType));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MetsaregisterMapGatewayException("Metsaregister map request was interrupted", exception);
            } catch (IOException exception) {
                upstreamErrors.increment();
                last = new MetsaregisterMapGatewayException("Metsaregister map upstream is unavailable", exception);
                if (attempt == maxAttempts) throw last;
            } catch (MetsaregisterMapGatewayException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                upstreamErrors.increment();
                throw new MetsaregisterMapGatewayException("Metsaregister map request failed", exception);
            }
        }
        throw last == null ? new MetsaregisterMapGatewayException("Metsaregister map upstream is unavailable") : last;
    }

    private static String failureCode(RuntimeException exception) {
        if (exception instanceof MetsaregisterMapGatewayException gateway && gateway.upstreamStatus() > 0) {
            return "HTTP_" + gateway.upstreamStatus();
        }
        return exception.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static void closeBody(HttpResponse<InputStream> response) {
        if (response == null || response.body() == null) return;
        try { response.body().close(); } catch (IOException ignored) { }
    }

    private static String normalizeFormat(String format) {
        String value = format == null ? "image/png" : format.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_FORMATS.contains(value)) throw new IllegalArgumentException("Unsupported map image format: " + value);
        return value;
    }

    private static int validateDimension(int value, String name) {
        if (value < 64 || value > 1024) throw new IllegalArgumentException(name + " must be between 64 and 1024");
        return value;
    }

    private static Integer validateZoom(Integer zoom, int minZoom, int maxZoom) {
        if (zoom == null) return null;
        if (zoom < minZoom || zoom > maxZoom) throw new IllegalArgumentException("zoom must be between " + minZoom + " and " + maxZoom + " for this layer");
        return zoom;
    }

    record GatewayImage(byte[] body, MediaType contentType) { }

    static final class MetsaregisterMapGatewayException extends RuntimeException {
        private final int upstreamStatus;
        MetsaregisterMapGatewayException(String message) { this(message, 0, null); }
        MetsaregisterMapGatewayException(String message, Throwable cause) { this(message, 0, cause); }
        MetsaregisterMapGatewayException(String message, int upstreamStatus) { this(message, upstreamStatus, null); }
        private MetsaregisterMapGatewayException(String message, int upstreamStatus, Throwable cause) { super(message, cause); this.upstreamStatus = upstreamStatus; }
        int upstreamStatus() { return upstreamStatus; }
    }
}
