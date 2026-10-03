package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import ee.forestiq.cadastre.MapTileService;
import ee.forestiq.cadastre.ParcelMapSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MapSearchServiceTest {

    @Test
    void exactPublishedCadastreResolvesLocallyWithoutProviderCall() throws Exception {
        MapTileService mapTiles = mock(MapTileService.class);
        ParcelMapSummary parcel = mock(ParcelMapSummary.class);
        when(parcel.cadastralCode()).thenReturn("78401:101:0101");
        when(parcel.centroidLongitude()).thenReturn(24.75);
        when(parcel.centroidLatitude()).thenReturn(59.44);
        when(parcel.bboxWest()).thenReturn(24.74);
        when(parcel.bboxSouth()).thenReturn(59.43);
        when(parcel.bboxEast()).thenReturn(24.76);
        when(parcel.bboxNorth()).thenReturn(59.45);
        when(mapTiles.landParcelSummary("78401:101:0101")).thenReturn(parcel);
        HttpClient client = mock(HttpClient.class);
        MapSearchService service = service(mapTiles, client);

        var results = service.search("78401:101:0101", 8);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().type()).isEqualTo("CADASTRE");
        assertThat(results.getFirst().local()).isTrue();
        assertThat(results.getFirst().cadastralCode()).isEqualTo("78401:101:0101");
        verify(client, never()).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void addressProviderResponseIsNormalizedAndBounded() throws Exception {
        MapTileService mapTiles = mock(MapTileService.class);
        HttpClient client = mock(HttpClient.class);
        String json = """
            {"addresses":[
              {"pikkaadress":"Harju maakond, Tallinn, Timuti tn 15","liikVal":"HOONE","adr_id":"A1","viitepunkt_l":24.75,"viitepunkt_b":59.44},
              {"aadresstekst":"Timuti tn 17","liikVal":"HOONE","adr_id":"A2","viitepunkt_l":"24.76","viitepunkt_b":"59.45"}
            ]}
            """;
        stubSend(client, response(200, "application/json; charset=utf-8", json.getBytes()));
        MapSearchService service = service(mapTiles, client);

        var results = service.search("Timuti 15", 1);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().type()).isEqualTo("ADDRESS");
        assertThat(results.getFirst().displayLabel()).contains("Timuti tn 15");
        assertThat(results.getFirst().longitude()).isEqualTo(24.75);
        assertThat(results.getFirst().provider()).isEqualTo("MAARUUM_INAKS");
        assertThat(results.getFirst().local()).isFalse();
    }

    @Test
    void missingLocalExactCadastreFallsBackToProvider() throws Exception {
        MapTileService mapTiles = mock(MapTileService.class);
        when(mapTiles.landParcelSummary("78401:101:9999"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        HttpClient client = mock(HttpClient.class);
        String json = """
            {"addresses":[{"pikkaadress":"78401:101:9999","liikVal":"KATASTRIYKSUS","tunnus":"78401:101:9999","adr_id":"K1","viitepunkt_l":24.7,"viitepunkt_b":59.4}]}
            """;
        stubSend(client, response(200, "application/json", json.getBytes()));
        MapSearchService service = service(mapTiles, client);

        var results = service.search("78401:101:9999", 8);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().type()).isEqualTo("CADASTRE");
        assertThat(results.getFirst().local()).isFalse();
        verify(client, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @Test
    void malformedProviderPayloadFailsClosedAndQueryLimitsAreValidated() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubSend(client, response(200, "application/json", "{\"unexpected\":[]}".getBytes()));
        MapSearchService service = service(mock(MapTileService.class), client);

        assertThatThrownBy(() -> service.search("Timuti", 8))
            .isInstanceOf(MapSearchService.MapSearchUnavailableException.class)
            .hasMessageContaining("schema");
        assertThatThrownBy(() -> service.search("a", 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("Timuti", 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerFiveHundredRetriesButFourHundredDoesNot() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        properties.getMaaamet().setMaxAttempts(2);
        MapTileService mapTiles = mock(MapTileService.class);
        HttpClient retryClient = mock(HttpClient.class);
        stubSend(retryClient, 
            response(503, "application/json", "{}".getBytes()),
            response(200, "application/json", "{\"addresses\":[]}".getBytes()));
        MapSearchService retryService = new MapSearchService(properties, mapTiles, new ObjectMapper(), new SimpleMeterRegistry(), retryClient);

        assertThat(retryService.search("Timuti", 8)).isEmpty();
        verify(retryClient, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());

        HttpClient client4xx = mock(HttpClient.class);
        stubSend(client4xx, response(400, "application/json", "{}".getBytes()));
        MapSearchService service4xx = new MapSearchService(properties, mapTiles, new ObjectMapper(), new SimpleMeterRegistry(), client4xx);
        assertThatThrownBy(() -> service4xx.search("Timuti", 8))
            .isInstanceOf(MapSearchService.MapSearchUnavailableException.class)
            .hasMessageContaining("HTTP 400");
        verify(client4xx, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    private static MapSearchService service(MapTileService mapTiles, HttpClient client) {
        return new MapSearchService(new RegistryProperties(), mapTiles, new ObjectMapper(), new SimpleMeterRegistry(), client);
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
