package ee.forestiq.portfolio;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Streams the MetsIS/Forestek owner portfolio projection. Forestek's {@code /api/owners-test}
 * returns flat owner–cadastre rows; this client groups them into per-owner portfolios.
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "MetsIS owner portfolio ingestion remains Java-only until Python shadow parity is complete."
)
@Service
public class MetsisPortfolioClient {
    private final MetsisPortfolioProperties properties;
    private final RestClient client;

    public MetsisPortfolioClient(MetsisPortfolioProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public PageResult fetchPage(int page, int expectedPageSize) {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MetsIS portfelliühendus ei ole seadistatud.");
        }
        // Legacy OwnersOwningsSynchronizer uses 1-based paging: p=1 is the first page.
        // The legacy endpoint does not accept our configured page size, therefore a
        // short non-empty response cannot be interpreted as the final page. Continue
        // until the provider explicitly returns an empty page; max-pages-per-run in
        // MetsisPortfolioSyncService remains the safety guard against a broken pager.
        int upstreamPage = Math.max(0, page) + 1;
        try {
            return withRetry(() -> {
                URI uri = UriComponentsBuilder.fromUriString(properties.getUri())
                    .queryParam("p", upstreamPage)
                    .build(true)
                    .toUri();
                JsonNode response = client.get().uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("test", properties.getAuthorization())
                    .retrieve()
                    .body(JsonNode.class);
                if (response == null || !response.isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MetsIS portfelli API ei tagastanud omanike massiivi.");
                }
                if (response.isEmpty()) {
                    return new PageResult(List.of(), 0, false);
                }
                JsonNode first = response.get(0);
                // Forestek legacy: [{ownerId, ownerName, cadastre}, ...]
                if (first.has("ownerId") || first.has("ownerName") || first.has("cadastre")) {
                    List<PortfolioRow> rows = groupFlatRelations(response);
                    return new PageResult(rows, response.size(), true);
                }
                // Simo-style nested portfolios kept for compatibility.
                if (first.has("owner")) {
                    List<PortfolioRow> rows = parseNestedPortfolios(response);
                    return new PageResult(rows, rows.size(), true);
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MetsIS portfelli API tagastas tundmatu rea struktuuri.");
            });
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MetsIS portfelli API päring ebaõnnestus", exception);
        }
    }

    private List<PortfolioRow> groupFlatRelations(JsonNode response) {
        Map<String, PortfolioAccumulator> byOwner = new LinkedHashMap<>();
        for (JsonNode row : response) {
            String code = text(row, "ownerId");
            String name = text(row, "ownerName");
            String cadastre = text(row, "cadastre");
            if (code.isBlank() || name.isBlank() || cadastre.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MetsIS portfelli API tagastas puuduliku omaniku-katastri rea.");
            }
            PortfolioAccumulator accumulator = byOwner.computeIfAbsent(code, key -> new PortfolioAccumulator(name, inferOwnerType(code)));
            accumulator.cadastres.add(cadastre);
        }
        List<PortfolioRow> rows = new ArrayList<>(byOwner.size());
        for (Map.Entry<String, PortfolioAccumulator> entry : byOwner.entrySet()) {
            PortfolioAccumulator value = entry.getValue();
            rows.add(new PortfolioRow(entry.getKey(), value.name, value.ownerType, List.copyOf(value.cadastres)));
        }
        return rows;
    }

    private List<PortfolioRow> parseNestedPortfolios(JsonNode response) {
        List<PortfolioRow> rows = new ArrayList<>();
        for (JsonNode row : response) {
            JsonNode owner = row.path("owner");
            String code = owner.path("code").asText("").trim();
            String name = owner.path("name").asText("").trim();
            String type = owner.path("type").asText("").trim();
            if (code.isBlank() || name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MetsIS portfelli API tagastas puuduliku omaniku rea.");
            }
            List<String> activeCadastres = new ArrayList<>();
            JsonNode ownings = row.path("activeOwnings");
            if (!ownings.isMissingNode() && !ownings.isNull() && !ownings.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MetsIS portfelli API tagastas vigase activeOwnings välja.");
            }
            if (ownings.isArray()) {
                for (JsonNode owning : ownings) {
                    String cadastralCode = owning.path("cadastreNo").asText("").trim();
                    if (cadastralCode.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "MetsIS portfelli API tagastas puuduliku katastri rea.");
                    }
                    activeCadastres.add(cadastralCode);
                }
            }
            rows.add(new PortfolioRow(code, name, type, List.copyOf(activeCadastres)));
        }
        return rows;
    }

    private static String inferOwnerType(String ownerCode) {
        String normalized = ownerCode.replace(" ", "");
        if (normalized.matches("\\d{8}")) {
            return "COMPANY";
        }
        return "PERSON";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private <T> T withRetry(Supplier<T> request) {
        RuntimeException last = null;
        int attempts = Math.max(1, Math.min(properties.getMaxAttempts(), 5));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("MetsIS portfelli päring katkestati") : last;
    }

    private static final class PortfolioAccumulator {
        private final String name;
        private final String ownerType;
        private final List<String> cadastres = new ArrayList<>();

        private PortfolioAccumulator(String name, String ownerType) {
            this.name = name;
            this.ownerType = ownerType;
        }
    }

    public record PageResult(List<PortfolioRow> rows, int rawRowCount, boolean hasMore) { }

    public record PortfolioRow(String ownerCode, String ownerName, String ownerType, List<String> activeCadastres) { }
}
