package ee.forestiq.geospatial.etak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class EtakAdminWfsClientTest {

    @Test
    void pageIsBoundedAndRequestsExplicitPaging() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, response(200, "application/json", """
            {"type":"FeatureCollection","timeStamp":"2026-09-07T10:00:00Z","features":[
              {"type":"Feature","properties":{"etak_id":"1"},"geometry":{"type":"Polygon","coordinates":[]}}
            ]}
            """.getBytes()));
        EtakAdminWfsClient client = new EtakAdminWfsClient(new ObjectMapper(), http, 5000, 1024 * 1024, 2, Duration.ofSeconds(5));

        var page = client.fetchPage(layer(), 10_000);

        assertThat(page.features()).hasSize(1);
        assertThat(page.maybeMore()).isFalse();
        assertThat(page.providerTimestamp()).isEqualTo("2026-09-07T10:00:00Z");
        verify(http).send(
            ArgumentMatchers.argThat((HttpRequest request) -> request.uri().toString().contains("count=5000")
                && request.uri().toString().contains("startIndex=10000")
                && request.uri().toString().contains("srsName=EPSG:3301")),
            ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void retriesServerFailureButRejectsNonJsonAndOversizedPages() throws Exception {
        HttpClient retry = mock(HttpClient.class);
        stubSend(retry, 
            response(503, "text/plain", "down".getBytes()),
            response(200, "application/json", "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes()));
        EtakAdminWfsClient retryClient = new EtakAdminWfsClient(new ObjectMapper(), retry, 5000, 1024 * 1024, 2, Duration.ofSeconds(5));
        assertThat(retryClient.fetchPage(layer(), 0).features()).isEmpty();
        verify(retry, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());

        HttpClient html = mock(HttpClient.class);
        stubSend(html, response(200, "text/html", "<html>provider error</html>".getBytes()));
        EtakAdminWfsClient htmlClient = new EtakAdminWfsClient(new ObjectMapper(), html, 5000, 1024 * 1024, 1, Duration.ofSeconds(5));
        assertThatThrownBy(() -> htmlClient.fetchPage(layer(), 0))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("non-JSON");

        HttpClient large = mock(HttpClient.class);
        stubSend(large, response(200, "application/json", new byte[2049]));
        EtakAdminWfsClient largeClient = new EtakAdminWfsClient(new ObjectMapper(), large, 5000, 2048, 1, Duration.ofSeconds(5));
        assertThatThrownBy(() -> largeClient.fetchPage(layer(), 0))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("byte budget");
    }

    @Test
    void constructorRejectsPageSizesAboveProviderContract() {
        assertThatThrownBy(() -> new EtakAdminWfsClient(
            new ObjectMapper(), mock(HttpClient.class), 5001, 1024 * 1024, 1, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5000");
    }

    private static EtakAdminLayerManifestService.Layer layer() {
        return new EtakAdminLayerManifestService.Layer(
            "MARU_ETAK_WOODY", "ETAK — puittaimestik", "ETAK",
            "https://gsavalik.envir.ee/geoserver/etak/wfs", "etak:E_305_puittaimestik_a",
            true, null, "context", "POLYGON", 3301, "etak_id", List.of("etak_id"),
            604800, 864000, "WFS_PAGED", true, true, 11, 16, 0.3, 1, 10_000_000,
            "topographic only");
    }

    @SafeVarargs
    private static void stubSend(HttpClient client, HttpResponse<InputStream>... responses) throws Exception {
        var stubbing = whenSend(client);
        for (var response : responses) {
            stubbing = stubbing.thenReturn(response);
        }
    }

    private static org.mockito.stubbing.OngoingStubbing<HttpResponse<InputStream>> whenSend(HttpClient client) throws Exception {
        return when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, String contentType, byte[] body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of(contentType)), (name, value) -> true));
        when(response.body()).thenAnswer(invocation -> new ByteArrayInputStream(body));
        return response;
    }
}
