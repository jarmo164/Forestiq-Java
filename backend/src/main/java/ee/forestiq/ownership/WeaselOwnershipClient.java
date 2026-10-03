package ee.forestiq.ownership;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** Pulls only authenticated cadastral ownership-transition events from Weasel. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Weasel ownership ingestion remains Java-only until owner-domain source cutover evidence is complete."
)
@Service
public class WeaselOwnershipClient {
    private static final Logger log = LoggerFactory.getLogger(WeaselOwnershipClient.class);
    private static final long MAX_RETRY_SLEEP_MILLIS = Duration.ofMinutes(5).toMillis();

    private final WeaselProperties properties;
    private final RestClient client;

    public WeaselOwnershipClient(WeaselProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public TransitionPage fetchTransitions(Instant from, Instant to, int pageSize, int pageNo) {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Weaseli omandiüleminekute teenusekonto ei ole seadistatud.");
        }
        try {
            return withRetry(() -> {
                URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                    .pathSegment("owning-transitions")
                    .queryParam("startTime", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                    .queryParam("endTime", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                    .queryParam("maxItemsOnPage", Math.max(1, Math.min(pageSize, 250)))
                    .queryParam("pageNo", Math.max(1, pageNo))
                    .build(true).toUri();
                JsonNode response = client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getAccessToken()).retrieve().body(JsonNode.class);
                return parseResponse(response);
            });
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Weaseli omandiüleminekute päring ebaõnnestus", exception);
        }
    }

    static TransitionPage parseResponse(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw malformedPayload("vastuse juur ei ole JSON objekt");
        }
        JsonNode rawItems = response.get("items");
        if (rawItems == null || !rawItems.isArray()) {
            throw malformedPayload("väli 'items' puudub või ei ole massiiv");
        }

        List<OwnershipTransition> items = new ArrayList<>();
        int index = 0;
        for (JsonNode item : rawItems) {
            if (item == null || !item.isObject()) {
                throw malformedPayload("items[" + index + "] ei ole JSON objekt");
            }

            JsonNode idNode = item.get("id");
            if (idNode == null || !idNode.isIntegralNumber() || !idNode.canConvertToLong() || idNode.longValue() <= 0) {
                throw malformedPayload("items[" + index + "].id puudub või on vigane");
            }
            long id = idNode.longValue();

            JsonNode cadastreNode = item.get("cadastreNo");
            if (cadastreNode == null || !cadastreNode.isTextual() || cadastreNode.asText().trim().isBlank()) {
                throw malformedPayload("items[" + index + "].cadastreNo puudub või on tühi");
            }
            String cadastre = cadastreNode.asText().trim();

            JsonNode createdTimeNode = item.get("createdTime");
            if (createdTimeNode == null || !createdTimeNode.isTextual()) {
                throw malformedPayload("items[" + index + "].createdTime puudub või on vigane");
            }
            Instant createdAt = parseInstant(createdTimeNode.asText());
            if (createdAt == null) {
                throw malformedPayload("items[" + index + "].createdTime ei ole kehtiv ajatempel");
            }

            items.add(new OwnershipTransition(id, cadastre, createdAt,
                textList(item.path("oldOwners")), textList(item.path("newOwners"))));
            index++;
        }
        return new TransitionPage(response.path("totalItems").asInt(items.size()), List.copyOf(items));
    }

    private static ResponseStatusException malformedPayload(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Weaseli omandiüleminekute vastus on vigane: " + detail + ".");
    }

    private <T> T withRetry(Supplier<T> request) {
        int maxAttempts = Math.max(1, Math.min(properties.getMaxAttempts(), 5));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException exception) {
                if (!isRetryableStatus(exception.getStatusCode()) || attempt >= maxAttempts) {
                    throw exception;
                }
                long delayMillis = retryDelayMillis(exception, attempt, Instant.now());
                if (delayMillis > MAX_RETRY_SLEEP_MILLIS) {
                    log.warn("Weasel requested a retry delay of {} ms on attempt {}/{}; not retrying inside this run",
                        delayMillis, attempt, maxAttempts);
                    throw exception;
                }
                log.warn("Weasel HTTP {} on attempt {}/{}; retrying in {} ms",
                    exception.getStatusCode().value(), attempt, maxAttempts, delayMillis);
                sleep(delayMillis, exception);
            } catch (ResourceAccessException exception) {
                if (attempt >= maxAttempts) throw exception;
                long delayMillis = fallbackDelayMillis(attempt);
                log.warn("Weasel network request failed on attempt {}/{}; retrying in {} ms",
                    attempt, maxAttempts, delayMillis);
                sleep(delayMillis, exception);
            }
        }
        throw new IllegalStateException("Weaseli päring katkestati");
    }

    static boolean isRetryableStatus(HttpStatusCode status) {
        int value = status.value();
        return value == HttpStatus.REQUEST_TIMEOUT.value()
            || value == HttpStatus.TOO_MANY_REQUESTS.value()
            || status.is5xxServerError();
    }

    static long retryDelayMillis(RestClientResponseException exception, int attempt, Instant now) {
        HttpHeaders headers = exception.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        Long serverDelay = parseRetryAfterMillis(retryAfter, now);
        return serverDelay == null ? fallbackDelayMillis(attempt) : serverDelay;
    }

    static Long parseRetryAfterMillis(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) return null;
            return Math.multiplyExact(seconds, 1000L);
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Retry-After may also be an RFC 1123 HTTP-date.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(now, retryAt).toMillis());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long fallbackDelayMillis(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 5));
        long base = Math.min(30_000L, 1_000L << exponent);
        long jitter = ThreadLocalRandom.current().nextLong(0L, 251L);
        return base + jitter;
    }

    private static void sleep(long millis, RuntimeException original) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw original;
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
            }
        }
        return result;
    }

    public record OwnershipTransition(long externalId, String cadastralCode, Instant occurredAt,
                                      List<String> oldOwnerIdentifiers, List<String> newOwnerIdentifiers) { }
    public record TransitionPage(int totalItems, List<OwnershipTransition> items) { }
}
