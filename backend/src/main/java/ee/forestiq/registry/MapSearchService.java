package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import ee.forestiq.cadastre.MapTileService;
import ee.forestiq.cadastre.ParcelMapSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Stable ForestIQ search boundary over local published cadastre and Maa- ja Ruumiamet In-AKS. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.REAL_TIME_DOMAIN_OPERATION,
    reason = "Map search is an interactive lookup boundary, not scheduled batch ETL ingestion."
)
@Service
public class MapSearchService {
    static final Pattern CADASTRAL_CODE = Pattern.compile("^\\d{5}:\\d{3}:\\d{4}$");
    private static final int MAX_PROVIDER_BYTES = 512 * 1024;

    private final RegistryProperties properties;
    private final MapTileService mapTiles;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Cache<String, List<MapSearchResult>> cache;
    private final MeterRegistry meters;
    private final Timer providerLatency;

    @Autowired
    MapSearchService(RegistryProperties properties,
                     MapTileService mapTiles,
                     ObjectMapper objectMapper,
                     MeterRegistry meters) {
        this(properties, mapTiles, objectMapper, meters, null);
    }

    MapSearchService(RegistryProperties properties,
                     MapTileService mapTiles,
                     ObjectMapper objectMapper,
                     MeterRegistry meters,
                     HttpClient suppliedHttpClient) {
        this.properties = properties;
        this.mapTiles = mapTiles;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.httpClient = suppliedHttpClient == null
            ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(properties.getMaaamet().getConnectTimeoutSeconds(), 30))))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            : suppliedHttpClient;
        this.cache = Caffeine.<String, List<MapSearchResult>>newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
        this.providerLatency = Timer.builder("forestiq.maaruum.search.upstream.latency")
            .description("In-AKS search latency")
            .register(meters);
    }

    List<MapSearchResult> search(String rawQuery, int requestedLimit) {
        String query = normalizeQuery(rawQuery);
        int limit = validateLimit(requestedLimit);
        if (CADASTRAL_CODE.matcher(query).matches()) {
            MapSearchResult local = localCadastre(query);
            if (local != null) {
                meters.counter("forestiq.map.search.requests", "route", "local-cadastre", "result", "hit").increment();
                return List.of(local);
            }
        }

        String cacheKey = query.toLowerCase(Locale.ROOT) + '|' + limit;
        List<MapSearchResult> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            meters.counter("forestiq.map.search.requests", "route", "inaks", "result", "cache-hit").increment();
            return cached;
        }
        List<MapSearchResult> results = fetchProvider(query, limit);
        cache.put(cacheKey, results);
        meters.counter("forestiq.map.search.requests", "route", "inaks", "result", results.isEmpty() ? "empty" : "success").increment();
        return results;
    }

    private MapSearchResult localCadastre(String code) {
        try {
            ParcelMapSummary parcel = mapTiles.landParcelSummary(code);
            return new MapSearchResult(
                "CADASTRE", code, parcel.centroidLongitude(), parcel.centroidLatitude(),
                parcel.bboxWest(), parcel.bboxSouth(), parcel.bboxEast(), parcel.bboxNorth(),
                "FORESTIQ", code, code, true);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return null;
            throw exception;
        }
    }

    private List<MapSearchResult> fetchProvider(String query, int limit) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getMaaamet().getInaksUrl())
            .replaceQuery(null)
            .queryParam("address", query)
            .build()
            .encode()
            .toUri();
        int attempts = Math.max(1, Math.min(properties.getMaaamet().getMaxAttempts(), 3));
        MapSearchUnavailableException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", "ForestIQ/In-AKS")
                    .GET()
                    .build();
                HttpResponse<InputStream> response = providerLatency.recordCallable(
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()));
                if (response == null) throw new MapSearchUnavailableException("In-AKS returned no response");
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    closeBody(response);
                    last = new MapSearchUnavailableException("In-AKS returned HTTP " + status, status);
                    if (status >= 500 && attempt < attempts) continue;
                    throw last;
                }
                String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                if (!contentType.contains("json")) {
                    closeBody(response);
                    throw new MapSearchUnavailableException("In-AKS returned a non-JSON response");
                }
                InputStream stream = response.body();
                if (stream == null) throw new MapSearchUnavailableException("In-AKS returned an empty response");
                byte[] bytes;
                try (stream) { bytes = stream.readNBytes(MAX_PROVIDER_BYTES + 1); }
                if (bytes.length == 0 || bytes.length > MAX_PROVIDER_BYTES) {
                    throw new MapSearchUnavailableException("In-AKS response exceeded the configured limit");
                }
                return normalizeProviderResults(bytes, limit);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MapSearchUnavailableException("In-AKS request was interrupted", exception);
            } catch (IOException exception) {
                last = new MapSearchUnavailableException("In-AKS is unavailable", exception);
                if (attempt == attempts) throw last;
            } catch (MapSearchUnavailableException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new MapSearchUnavailableException("In-AKS request failed", exception);
            }
        }
        throw last == null ? new MapSearchUnavailableException("In-AKS is unavailable") : last;
    }

    private List<MapSearchResult> normalizeProviderResults(byte[] body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode addresses = root.path("addresses");
            if (!addresses.isArray()) throw new MapSearchUnavailableException("In-AKS response schema is invalid");
            List<MapSearchResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode address : addresses) {
                String label = firstText(address, "pikkaadress", "aadresstekst");
                Double longitude = finiteDouble(address, "viitepunkt_l");
                Double latitude = finiteDouble(address, "viitepunkt_b");
                if (label == null || longitude == null || latitude == null) continue;
                String typeValue = text(address, "liikVal");
                String cadastralCode = text(address, "tunnus");
                boolean cadastre = (typeValue != null && typeValue.toUpperCase(Locale.ROOT).contains("KATASTR"))
                    || (cadastralCode != null && CADASTRAL_CODE.matcher(cadastralCode).matches());
                if (!cadastre || cadastralCode == null || !CADASTRAL_CODE.matcher(cadastralCode).matches()) {
                    cadastralCode = null;
                }
                String identity = firstText(address, "adr_id", "ads_oid", "tunnus");
                String dedupeKey = (identity == null ? label : identity) + '|' + longitude + '|' + latitude;
                if (!seen.add(dedupeKey)) continue;
                results.add(new MapSearchResult(
                    cadastre ? "CADASTRE" : "ADDRESS", label, longitude, latitude,
                    null, null, null, null, "MAARUUM_INAKS", identity, cadastralCode, false));
                if (results.size() >= limit) break;
            }
            return List.copyOf(results);
        } catch (MapSearchUnavailableException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MapSearchUnavailableException("In-AKS response could not be parsed", exception);
        }
    }

    private static String normalizeQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().replaceAll("\\s+", " ");
        if (query.length() < 2 || query.length() > 120) {
            throw new IllegalArgumentException("Search query must contain 2 to 120 characters");
        }
        return query;
    }

    private static int validateLimit(int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("Search result limit must be between 1 and 10");
        return limit;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Double finiteDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        double number;
        if (value.isNumber()) number = value.asDouble();
        else {
            try { number = Double.parseDouble(value.asText().replace(',', '.')); }
            catch (NumberFormatException ignored) { return null; }
        }
        return Double.isFinite(number) ? number : null;
    }

    private static void closeBody(HttpResponse<InputStream> response) {
        if (response == null || response.body() == null) return;
        try { response.body().close(); } catch (IOException ignored) { }
    }

    public record MapSearchResult(
        String type,
        String displayLabel,
        Double longitude,
        Double latitude,
        Double west,
        Double south,
        Double east,
        Double north,
        String provider,
        String providerIdentity,
        String cadastralCode,
        boolean local
    ) { }

    static final class MapSearchUnavailableException extends RuntimeException {
        private final int upstreamStatus;
        MapSearchUnavailableException(String message) { this(message, 0, null); }
        MapSearchUnavailableException(String message, int upstreamStatus) { this(message, upstreamStatus, null); }
        MapSearchUnavailableException(String message, Throwable cause) { this(message, 0, cause); }
        private MapSearchUnavailableException(String message, int upstreamStatus, Throwable cause) {
            super(message, cause);
            this.upstreamStatus = upstreamStatus;
        }
        int upstreamStatus() { return upstreamStatus; }
    }
}
