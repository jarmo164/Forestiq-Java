package ee.forestiq.registry;

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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MaaruumBasemapServiceTest {

    @Test
    void convertsBrowserXyzYToOfficialTmsY() {
        RegistryProperties properties = new RegistryProperties();
        MaaruumBasemapService service = new MaaruumBasemapService(properties, new SimpleMeterRegistry(), mock(HttpClient.class));

        String uri = service.upstreamUri("kaart", 6, 28, 36).toString();

        assertThat(uri).endsWith("/kaart@GMC/6/28/27.png");
    }

    @Test
    void catalogExposesApprovedLayersWithoutProviderUrlAndKeepsReliefDisabledByDefault() {
        MaaruumBasemapService service = new MaaruumBasemapService(new RegistryProperties(), new SimpleMeterRegistry(), mock(HttpClient.class));

        var descriptors = service.descriptors();

        assertThat(descriptors).extracting(MaaruumBasemapService.BasemapDescriptor::key)
            .containsExactly("kaart", "ortofoto", "hybriid");
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.tileUrlTemplate()).startsWith("/api/v1/map/basemaps/");
            assertThat(descriptor.tileUrlTemplate()).doesNotContain("tiles.maaamet.ee");
        });
    }

    @Test
    void successfulTileIsCachedAndProviderIsCalledOnlyOnce() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubSend(client, response(200, "image/png", new byte[] {1, 2, 3}));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MaaruumBasemapService service = new MaaruumBasemapService(new RegistryProperties(), meters, client);

        assertThat(service.tile("kaart", 6, 28, 36).body()).containsExactly(1, 2, 3);
        assertThat(service.tile("kaart", 6, 28, 36).body()).containsExactly(1, 2, 3);

        verify(client, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(service.estimatedCacheSize()).isEqualTo(1);
        assertThat(meters.get("forestiq.maaruum.basemap.cache.requests").tags("layer", "kaart", "result", "hit").counter().count()).isEqualTo(1.0);
    }

    @Test
    void fourHundredErrorIsNotRetriedAndInvalidContentIsNeverCached() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        properties.getMaaamet().setMaxAttempts(3);
        HttpClient client = mock(HttpClient.class);
        stubSend(client, response(404, "text/plain", "missing".getBytes()));
        MaaruumBasemapService service = new MaaruumBasemapService(properties, new SimpleMeterRegistry(), client);

        assertThatThrownBy(() -> service.tile("kaart", 6, 28, 36))
            .isInstanceOf(MaaruumBasemapService.BasemapUnavailableException.class)
            .hasMessageContaining("HTTP 404");
        verify(client, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(service.estimatedCacheSize()).isZero();

        HttpClient htmlClient = mock(HttpClient.class);
        stubSend(htmlClient, response(200, "text/html", "error".getBytes()));
        MaaruumBasemapService htmlService = new MaaruumBasemapService(properties, new SimpleMeterRegistry(), htmlClient);
        assertThatThrownBy(() -> htmlService.tile("kaart", 6, 28, 36))
            .isInstanceOf(MaaruumBasemapService.BasemapUnavailableException.class)
            .hasMessageContaining("content type");
        assertThat(htmlService.estimatedCacheSize()).isZero();
    }

    @Test
    void serverErrorRetriesWithinConfiguredBound() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        properties.getMaaamet().setMaxAttempts(2);
        HttpClient client = mock(HttpClient.class);
        stubSend(client, 
            response(503, "text/plain", "down".getBytes()),
            response(200, "image/jpeg", new byte[] {4, 5}));
        MaaruumBasemapService service = new MaaruumBasemapService(properties, new SimpleMeterRegistry(), client);

        assertThat(service.tile("ortofoto", 6, 28, 36).body()).containsExactly(4, 5);
        verify(client, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
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
