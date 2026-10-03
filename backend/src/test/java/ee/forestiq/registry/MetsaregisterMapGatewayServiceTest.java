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
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetsaregisterMapGatewayServiceTest {
    private static final String ESTONIA_3857_BBOX = "2700000,8000000,2710000,8010000";

    private final RegistryProperties properties = new RegistryProperties();
    private final MetsaregisterLayerManifestService manifest =
        new MetsaregisterLayerManifestService(new ObjectMapper());

    @Test
    void rejectsUnknownOrNonMapLayersBeforeCallingProvider() {
        HttpClient client = mock(HttpClient.class);
        var gateway = gateway(new SimpleMeterRegistry(), client);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:not-approved", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:eraldis_element", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidBboxCrsDimensionsFormatAndZoomBeforeCallingProvider() {
        HttpClient client = mock(HttpClient.class);
        var gateway = gateway(new SimpleMeterRegistry(), client);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", "1,1,0,0", 256, 256, "EPSG:3857", "image/png"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bbox");
        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:4326", "image/png"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CRS");
        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 2048, 256, "EPSG:3857", "image/png"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("width");
        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "text/html"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("format");
        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 6))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zoom");
    }

    @Test
    void validImageIsCachedAndMetricsExposeHitsMissesBytesAndWeight() throws Exception {
        HttpClient client = mock(HttpClient.class);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        HttpResponse<InputStream> ok = response(200, "image/png; charset=binary", png);
        stubSend(client, ok);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var gateway = gateway(meters, client);

        var first = gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10);
        var second = gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10);

        assertThat(first.body()).containsExactly(png);
        assertThat(second.body()).containsExactly(png);
        assertThat(gateway.estimatedCacheSize()).isEqualTo(1);
        assertThat(gateway.estimatedCacheWeightBytes()).isEqualTo(png.length);
        assertThat(meters.get("forestiq.metsaregister.map.requests").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("forestiq.metsaregister.map.cache.hits").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("forestiq.metsaregister.map.cache.misses").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("forestiq.metsaregister.map.upstream.bytes").counter().count()).isEqualTo(png.length);
        verify(client, times(1)).send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void retriesServerErrorsButDoesNotCacheErrorPayloads() throws Exception {
        properties.getMetsaregister().setMaxAttempts(2);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> unavailable = response(503, "text/xml", "temporary".getBytes());
        HttpResponse<InputStream> ok = response(200, "image/png", new byte[] {1, 2, 3});
        stubSend(client, unavailable, ok);
        var gateway = gateway(new SimpleMeterRegistry(), client);

        var image = gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10);

        assertThat(image.body()).containsExactly(1, 2, 3);
        assertThat(gateway.estimatedCacheSize()).isEqualTo(1);
        verify(client, times(2)).send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void upstream4xxAnd5xxProduceControlledFailures() throws Exception {
        properties.getMetsaregister().setMaxAttempts(2);
        HttpClient client4xx = mock(HttpClient.class);
        HttpResponse<InputStream> missing = response(404, "text/xml", "missing".getBytes());
        stubSend(client4xx, missing);
        var gateway4xx = gateway(new SimpleMeterRegistry(), client4xx);

        assertThatThrownBy(() -> gateway4xx.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10))
            .isInstanceOf(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class)
            .hasMessageContaining("HTTP 404");
        verify(client4xx, times(1)).send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());

        HttpClient client5xx = mock(HttpClient.class);
        HttpResponse<InputStream> unavailableFirst = response(503, "text/xml", "down".getBytes());
        HttpResponse<InputStream> unavailableSecond = response(503, "text/xml", "down".getBytes());
        stubSend(client5xx, unavailableFirst, unavailableSecond);
        var gateway5xx = gateway(new SimpleMeterRegistry(), client5xx);

        assertThatThrownBy(() -> gateway5xx.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10))
            .isInstanceOf(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class)
            .hasMessageContaining("HTTP 503");
        verify(client5xx, times(2)).send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void timeoutRetriesThenProducesControlledFailure() throws Exception {
        properties.getMetsaregister().setMaxAttempts(2);
        HttpClient client = mock(HttpClient.class);
        whenSend(client).thenThrow(new HttpTimeoutException("provider timeout"));
        var gateway = gateway(new SimpleMeterRegistry(), client);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10))
            .isInstanceOf(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class)
            .hasMessageContaining("unavailable");
        verify(client, times(2)).send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void invalidUpstreamContentIsRejectedAndNeverCached() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> html = response(200, "text/html", "<html>error</html>".getBytes());
        stubSend(client, html);
        var gateway = gateway(new SimpleMeterRegistry(), client);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10))
            .isInstanceOf(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class)
            .hasMessageContaining("non-image");
        assertThat(gateway.estimatedCacheSize()).isZero();
    }

    @Test
    void externalProviderHealthIsIndependentFromLocalLayerAvailability() throws Exception {
        properties.getMetsaregister().setMaxAttempts(1);
        HttpClient client = mock(HttpClient.class);
        stubSend(client, response(503, "text/xml", "down".getBytes()));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var health = new MetsaregisterLayerHealthService();
        var gateway = new MetsaregisterMapGatewayService(
            properties, manifest, meters, client,
            new MetsaregisterSpatialContractService(manifest, meters), health);

        assertThatThrownBy(() -> gateway.getMap(
            "metsaregister:katsealad", ESTONIA_3857_BBOX, 256, 256, "EPSG:3857", "image/png", 10))
            .isInstanceOf(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class);

        assertThat(health.externalHealth("metsaregister:katsealad").availability()).isEqualTo("UNAVAILABLE");
        assertThat(health.forestryLocalHealth("6h").availability()).isEqualTo("UNKNOWN");
    }

    private MetsaregisterMapGatewayService gateway(SimpleMeterRegistry meters, HttpClient client) {
        return new MetsaregisterMapGatewayService(properties, manifest, meters, client);
    }

    @SafeVarargs
    private static void stubSend(HttpClient client, HttpResponse<InputStream>... responses) throws Exception {
        var stubbing = whenSend(client);
        for (var response : responses) {
            stubbing = stubbing.thenReturn(response);
        }
    }

    private static org.mockito.stubbing.OngoingStubbing<HttpResponse<InputStream>> whenSend(HttpClient client)
        throws Exception {
        return when(client.send(
            any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, String contentType, byte[] body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        Map<String, List<String>> headers = contentType == null
            ? Map.of()
            : Map.of("content-type", List.of(contentType));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenAnswer(invocation -> new ByteArrayInputStream(body));
        return response;
    }
}
