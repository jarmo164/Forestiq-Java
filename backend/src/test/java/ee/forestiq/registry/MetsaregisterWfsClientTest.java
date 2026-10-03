package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MetsaregisterWfsClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void advancesStartIndexUntilAllMatchedFeaturesAreReturned() {
        RegistryProperties properties = properties(2, 10);
        List<URI> requests = new ArrayList<>();
        AtomicInteger page = new AtomicInteger();
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties, uri -> {
            requests.add(uri);
            return page.getAndIncrement() == 0
                ? response(3, feature(1), feature(2))
                : response(3, feature(3));
        });
        List<Long> ids = new ArrayList<>();

        var summary = client.fetchPages("metsaregister:eraldis", "katastri_nr='10001:001:0001'",
            wfsPage -> wfsPage.features().forEach(feature -> ids.add(feature.path("properties").path("sys_id").asLong())));

        assertThat(summary.pages()).isEqualTo(2);
        assertThat(summary.records()).isEqualTo(3);
        assertThat(summary.numberMatched()).isEqualTo(3);
        assertThat(ids).containsExactly(1L, 2L, 3L);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).toString()).contains("count=2", "startIndex=0", "sortBy=sys_id%20A", "CQL_FILTER=");
        assertThat(requests.get(1).toString()).contains("count=2", "startIndex=2");
    }

    @Test
    void readsOnePageFromPersistedResumeCursor() {
        RegistryProperties properties = properties(2, 10);
        List<URI> requests = new ArrayList<>();
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties, uri -> {
            requests.add(uri);
            return response(7, feature(5), feature(6));
        });

        var page = client.fetchPage("metsaregister:eraldis",
            "katastri_nr IN ('10001:001:0001','10001:001:0002')", 4);

        assertThat(page.startIndex()).isEqualTo(4);
        assertThat(page.nextStartIndex()).isEqualTo(6);
        assertThat(page.records()).isEqualTo(2);
        assertThat(page.complete()).isFalse();
        assertThat(page.attempts()).isEqualTo(1);
        assertThat(requests).singleElement().satisfies(uri ->
            assertThat(uri.toString()).contains("startIndex=4", "count=2", "sortBy=sys_id%20A"));
    }

    @Test
    void fullLayerPageOmitsCqlAndUsesSchemaApprovedSortField() {
        RegistryProperties properties = properties(2, 10);
        List<URI> requests = new ArrayList<>();
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties, uri -> {
            requests.add(uri);
            return response(3, feature(1), feature(2));
        });

        var page = client.fetchFullPage("metsaregister:eraldis", "sys_id", 0);

        assertThat(page.records()).isEqualTo(2);
        assertThat(page.complete()).isFalse();
        assertThat(requests).singleElement().satisfies(uri -> {
            assertThat(uri.toString()).contains("typeNames=metsaregister:eraldis", "sortBy=sys_id%20A", "startIndex=0");
            assertThat(uri.toString()).doesNotContain("CQL_FILTER");
        });
    }

    @Test
    void reportsSuccessfulRetryAttemptCount() {
        RegistryProperties properties = properties(2, 10);
        properties.getMetsaregister().setMaxAttempts(2);
        AtomicInteger attempts = new AtomicInteger();
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties, uri -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException("temporary provider failure");
            return response(1, feature(42));
        });

        var page = client.fetchFullPage("metsaregister:eraldis", "sys_id", 0);

        assertThat(page.records()).isEqualTo(1);
        assertThat(page.attempts()).isEqualTo(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void pageReportsCompletionWhenProviderLimitWindowIsFullyConsumed() {
        RegistryProperties properties = properties(2, 10);
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties,
            uri -> response(5, feature(5)));

        var page = client.fetchPage("metsaregister:teatis", "katastri_nr='10001:001:0001'", 4);

        assertThat(page.nextStartIndex()).isEqualTo(5);
        assertThat(page.complete()).isTrue();
    }

    @Test
    void capsConfiguredPageSizeAtProviderLimit() {
        RegistryProperties properties = properties(2_000_000, 2);
        List<URI> requests = new ArrayList<>();
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties, uri -> {
            requests.add(uri);
            return response(0);
        });

        var summary = client.fetchPages("metsaregister:teatis", "katastri_nr='10001:001:0001'", page -> { });

        assertThat(summary.records()).isZero();
        assertThat(requests).singleElement().satisfies(uri ->
            assertThat(uri.toString()).contains("count=1000000", "startIndex=0"));
    }

    @Test
    void stopsWhenConfiguredPageSafetyLimitIsReached() {
        RegistryProperties properties = properties(1, 2);
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties,
            uri -> responseUnknownTotal(feature(1)));

        assertThatThrownBy(() -> client.fetchPages(
            "metsaregister:eraldis", "katastri_nr='10001:001:0001'", page -> { }))
            .isInstanceOf(ExternalRegistryService.RegistryException.class)
            .hasMessageContaining("ületas 2 lehekülje");
    }

    @Test
    void rejectsUnboundedQueries() {
        MetsaregisterWfsClient client = new MetsaregisterWfsClient(properties(100, 10), uri -> response(0));

        assertThatThrownBy(() -> client.fetchPages("metsaregister:eraldis", " ", page -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bounded CQL filter");
    }

    private RegistryProperties properties(int pageSize, int maxPages) {
        RegistryProperties properties = new RegistryProperties();
        properties.getMetsaregister().setWfsUrl("https://example.test/geoserver/metsaregister/ows");
        properties.getMetsaregister().setPageSize(pageSize);
        properties.getMetsaregister().setMaxPagesPerQuery(maxPages);
        properties.getMetsaregister().setMaxAttempts(1);
        properties.getMetsaregister().setMinRequestIntervalMs(0);
        return properties;
    }

    private ObjectNode response(long matched, JsonNode... features) {
        ObjectNode response = json.createObjectNode();
        response.put("numberMatched", matched);
        response.put("numberReturned", features.length);
        ArrayNode values = response.putArray("features");
        for (JsonNode feature : features) values.add(feature);
        return response;
    }

    private ObjectNode responseUnknownTotal(JsonNode... features) {
        ObjectNode response = json.createObjectNode();
        response.put("numberMatched", "unknown");
        response.put("numberReturned", features.length);
        ArrayNode values = response.putArray("features");
        for (JsonNode feature : features) values.add(feature);
        return response;
    }

    private ObjectNode feature(long id) {
        ObjectNode feature = json.createObjectNode();
        feature.putObject("properties").put("sys_id", id);
        return feature;
    }
}
