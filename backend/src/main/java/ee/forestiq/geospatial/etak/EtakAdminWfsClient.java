package ee.forestiq.geospatial.etak;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Bounded WFS 2.0 paging boundary. Provider payloads never escape this package. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "ETAK WFS ingestion remains Java-only until the geospatial source family is migrated to Python."
)
@Component
public class EtakAdminWfsClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final int pageSize;
    private final int maxPageBytes;
    private final int maxAttempts;
    private final Duration requestTimeout;

    @Autowired
    public EtakAdminWfsClient(
        ObjectMapper objectMapper,
        @Value("${forestiq.geospatial.wfs.connect-timeout-seconds:15}") long connectTimeoutSeconds,
        @Value("${forestiq.geospatial.wfs.request-timeout-seconds:90}") long requestTimeoutSeconds,
        @Value("${forestiq.geospatial.wfs.page-size:5000}") int pageSize,
        @Value("${forestiq.geospatial.wfs.max-page-bytes:33554432}") int maxPageBytes,
        @Value("${forestiq.geospatial.wfs.max-attempts:2}") int maxAttempts
    ) {
        this(
            objectMapper,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(connectTimeoutSeconds, "connect timeout")))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            pageSize,
            maxPageBytes,
            maxAttempts,
            Duration.ofSeconds(positive(requestTimeoutSeconds, "request timeout")));
    }

    EtakAdminWfsClient(ObjectMapper objectMapper,
                       HttpClient httpClient,
                       int pageSize,
                       int maxPageBytes,
                       int maxAttempts,
                       Duration requestTimeout) {
        if (pageSize < 1 || pageSize > 5000) {
            throw new IllegalArgumentException("WFS page size must be between 1 and 5000");
        }
        if (maxPageBytes < 1024 || maxPageBytes > 128 * 1024 * 1024) {
            throw new IllegalArgumentException("WFS page byte limit is outside safe bounds");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("WFS max attempts must be between 1 and 3");
        }
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.pageSize = pageSize;
        this.maxPageBytes = maxPageBytes;
        this.maxAttempts = maxAttempts;
        this.requestTimeout = requestTimeout;
    }

    public WfsPage fetchPage(EtakAdminLayerManifestService.Layer layer, long startIndex)
        throws IOException, InterruptedException {
        if (startIndex < 0) throw new IllegalArgumentException("WFS startIndex cannot be negative");
        URI uri = UriComponentsBuilder.fromUriString(layer.endpoint())
            .replaceQuery(null)
            .queryParam("service", "WFS")
            .queryParam("version", "2.0.0")
            .queryParam("request", "GetFeature")
            .queryParam("typeNames", layer.providerLayer())
            .queryParam("outputFormat", "application/json")
            .queryParam("srsName", "EPSG:3301")
            .queryParam("count", pageSize)
            .queryParam("startIndex", startIndex)
            .build()
            .encode()
            .toUri();

        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "application/geo+json,application/json")
                .header("User-Agent", "ForestIQ/2.0 geospatial-ingestion")
                .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                last = ex;
                if (attempt < maxAttempts) continue;
                throw ex;
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                IOException failure = new IOException("WFS provider returned HTTP " + status);
                if (status >= 500 && attempt < maxAttempts) {
                    last = failure;
                    continue;
                }
                throw failure;
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.contains("json")) {
                closeQuietly(response.body());
                throw new IOException("WFS provider returned non-JSON content");
            }
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxPageBytes) {
                closeQuietly(response.body());
                throw new IOException("WFS page exceeds byte budget");
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(maxPageBytes + 1);
            }
            if (bytes.length == 0 || bytes.length > maxPageBytes) {
                throw new IOException("WFS page is empty or exceeds byte budget");
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(bytes);
            } catch (Exception ex) {
                throw new IOException("WFS provider returned malformed JSON", ex);
            }
            JsonNode featureArray = root.path("features");
            if (!featureArray.isArray()) {
                throw new IOException("WFS FeatureCollection has no features array");
            }
            if (featureArray.size() > pageSize) {
                throw new IOException("WFS provider exceeded requested page size");
            }
            List<JsonNode> features = new ArrayList<>(featureArray.size());
            featureArray.forEach(features::add);
            String timestamp = root.path("timeStamp").asText(null);
            return new WfsPage(List.copyOf(features), timestamp, bytes.length, featureArray.size() == pageSize);
        }
        throw last == null ? new IOException("WFS provider is unavailable") : last;
    }

    public int pageSize() {
        return pageSize;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    public record WfsPage(List<JsonNode> features, String providerTimestamp, long bytes, boolean maybeMore) { }
}
