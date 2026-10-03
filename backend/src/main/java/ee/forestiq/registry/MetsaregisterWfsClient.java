package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Bounded, page-oriented client for the public Metsaregister WFS 2.0 service. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Metsaregister WFS ingestion remains Java-only until Python WFS cutover evidence is complete."
)
@Component
class MetsaregisterWfsClient {

    static final int PROVIDER_MAX_PAGE_SIZE = 1_000_000;
    private static final Pattern SAFE_TYPE_NAME = Pattern.compile("^[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$");
    private static final Pattern SAFE_FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final RegistryProperties.Metsaregister properties;
    private final PageFetcher pageFetcher;
    private long lastRequestNanos;

    @Autowired
    MetsaregisterWfsClient(RegistryProperties registryProperties) {
        this.properties = registryProperties.getMetsaregister();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        this.pageFetcher = uri -> client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
            .retrieve().body(JsonNode.class);
    }

    MetsaregisterWfsClient(RegistryProperties registryProperties, PageFetcher pageFetcher) {
        this.properties = registryProperties.getMetsaregister();
        this.pageFetcher = pageFetcher;
    }

    FetchSummary fetchPages(String typeName, String cqlFilter, Consumer<WfsPage> pageConsumer) {
        validateTypeName(typeName);
        validateCqlFilter(cqlFilter);
        if (pageConsumer == null) throw new IllegalArgumentException("WFS page consumer is required");
        long startIndex = 0;
        long totalReturned = 0;
        long numberMatched = -1;
        List<String> requestUrls = new ArrayList<>();
        int maxPages = Math.max(1, properties.getMaxPagesPerQuery());

        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            PageResult page = fetchPage(typeName, cqlFilter, startIndex);
            requestUrls.add(page.requestUrl());
            pageConsumer.accept(new WfsPage(pageNumber, startIndex, page.response(), page.features()));
            totalReturned += page.records();
            if (numberMatched < 0) numberMatched = page.numberMatched();
            if (page.complete()) {
                return new FetchSummary(pageNumber, totalReturned, numberMatched, List.copyOf(requestUrls));
            }
            startIndex = page.nextStartIndex();
        }
        throw new ExternalRegistryService.RegistryException(
            "Metsaregister WFS päring ületas " + maxPages + " lehekülje turvapiiri");
    }

    /** Reads one parcel-filtered page using the legacy configured sort field. */
    PageResult fetchPage(String typeName, String cqlFilter, long startIndex) {
        validateCqlFilter(cqlFilter);
        return fetchPageInternal(typeName, cqlFilter, properties.getSortField(), startIndex);
    }

    /** Reads one complete-layer page using schema-approved deterministic sort fields. */
    PageResult fetchFullPage(String typeName, String sortField, long startIndex) {
        return fetchPageInternal(typeName, null, sortField, startIndex);
    }

    private PageResult fetchPageInternal(String typeName, String cqlFilter, String sortField, long startIndex) {
        validateTypeName(typeName);
        List<String> sortFields = validateSortFields(sortField);
        if (startIndex < 0) throw new IllegalArgumentException("WFS startIndex must be non-negative");
        int pageSize = Math.max(1, Math.min(properties.getPageSize(), PROVIDER_MAX_PAGE_SIZE));

        URI uri = requestUri(typeName, cqlFilter, sortFields, pageSize, startIndex);
        RetryResult retried = withRetry(() -> {
            throttle();
            return pageFetcher.fetch(uri);
        });
        JsonNode response = retried.response();
        if (response == null || !response.path("features").isArray()) {
            throw new ExternalRegistryService.RegistryException("Metsaregister WFS tagastas vigase GeoJSON vastuse");
        }

        JsonNode features = response.path("features");
        int actualCount = features.size();
        long reportedCount = numeric(response.path("numberReturned"), actualCount);
        if (reportedCount != actualCount) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS numberReturned ei vasta GeoJSON kirjete arvule");
        }
        long numberMatched = numeric(response.path("numberMatched"), -1);
        long nextStartIndex = startIndex + actualCount;
        if (actualCount > 0 && nextStartIndex <= startIndex) {
            throw new ExternalRegistryService.RegistryException("Metsaregister WFS lehekülgimine ei edenenud");
        }
        boolean complete = actualCount == 0 || actualCount < pageSize
            || (numberMatched >= 0 && nextStartIndex >= numberMatched);
        return new PageResult(startIndex, nextStartIndex, actualCount, numberMatched, complete,
            uri.toString(), response, features, retried.attempts());
    }

    private URI requestUri(String typeName, String cqlFilter, List<String> sortFields, int pageSize, long startIndex) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.getWfsUrl())
            .queryParam("service", "WFS")
            .queryParam("version", "2.0.0")
            .queryParam("request", "GetFeature")
            .queryParam("typeNames", typeName)
            .queryParam("outputFormat", "application/json")
            .queryParam("srsName", "EPSG:4326");
        if (cqlFilter != null && !cqlFilter.isBlank()) {
            builder.queryParam("CQL_FILTER", cqlFilter);
        }
        String sortBy = sortFields.stream().map(field -> field + " A").collect(Collectors.joining(","));
        return builder
            .queryParam("sortBy", sortBy)
            .queryParam("count", pageSize)
            .queryParam("startIndex", startIndex)
            .encode()
            .build()
            .toUri();
    }

    private synchronized void throttle() {
        long minimumMs = Math.max(0, properties.getMinRequestIntervalMs());
        if (minimumMs == 0) {
            lastRequestNanos = System.nanoTime();
            return;
        }
        long elapsedMs = (System.nanoTime() - lastRequestNanos) / 1_000_000L;
        long sleepMs = minimumMs - elapsedMs;
        if (lastRequestNanos != 0 && sleepMs > 0) sleepInterruptibly(sleepMs);
        lastRequestNanos = System.nanoTime();
    }

    private RetryResult withRetry(Supplier<JsonNode> request) {
        int attempts = Math.max(1, Math.min(properties.getMaxAttempts(), 5));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ExternalRegistryService.RegistryException("Metsaregister WFS päring katkestati");
                }
                return new RetryResult(request.get(), attempt);
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < attempts) {
                    long base = Math.min(5_000L, 250L * (1L << Math.min(attempt - 1, 4)));
                    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 3L));
                    sleepInterruptibly(base + jitter);
                }
            }
        }
        throw new ExternalRegistryService.RegistryException(
            "Metsaregister WFS ei vastanud pärast " + attempts + " katset", last);
    }

    private static void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalRegistryService.RegistryException("Metsaregister WFS päring katkestati", interrupted);
        }
    }

    private static void validateTypeName(String typeName) {
        if (typeName == null || !SAFE_TYPE_NAME.matcher(typeName).matches()) {
            throw new IllegalArgumentException("Metsaregister WFS typeName is invalid");
        }
    }

    private static void validateCqlFilter(String cqlFilter) {
        if (cqlFilter == null || cqlFilter.isBlank()) {
            throw new IllegalArgumentException("Metsaregister WFS requires a bounded CQL filter");
        }
    }

    private static List<String> validateSortFields(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            throw new IllegalArgumentException("Metsaregister WFS sort field is invalid");
        }
        List<String> fields = Arrays.stream(sortField.split(",", -1))
            .map(String::trim)
            .toList();
        if (fields.isEmpty() || fields.size() > 8
            || fields.stream().anyMatch(field -> !SAFE_FIELD.matcher(field).matches())) {
            throw new IllegalArgumentException("Metsaregister WFS sort field is invalid");
        }
        return fields;
    }

    private static long numeric(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    @FunctionalInterface
    interface PageFetcher { JsonNode fetch(URI uri); }

    private record RetryResult(JsonNode response, int attempts) { }
    record WfsPage(int pageNumber, long startIndex, JsonNode response, JsonNode features) { }
    record PageResult(long startIndex, long nextStartIndex, int records, long numberMatched, boolean complete,
                      String requestUrl, JsonNode response, JsonNode features, int attempts) {
        PageResult(long startIndex, long nextStartIndex, int records, long numberMatched, boolean complete,
                   String requestUrl, JsonNode response, JsonNode features) {
            this(startIndex, nextStartIndex, records, numberMatched, complete, requestUrl, response, features, 1);
        }
    }
    record FetchSummary(int pages, long records, long numberMatched, List<String> requestUrls) {
        String lastRequestUrl() { return requestUrls.isEmpty() ? null : requestUrls.get(requestUrls.size() - 1); }
    }
}
