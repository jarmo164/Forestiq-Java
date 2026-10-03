package ee.forestiq.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MetsisPortfolioClientTest {

    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>("[]");
    private final AtomicReference<String> requestedQuery = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private MetsisPortfolioProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/owners", exchange -> {
            requestedQuery.set(exchange.getRequestURI().getQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("test"));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        properties = new MetsisPortfolioProperties();
        properties.setUri("http://127.0.0.1:" + server.getAddress().getPort() + "/owners");
        properties.setAuthorization("portfolio-test-token");
        properties.setMaxAttempts(1);
        properties.setTimeoutSeconds(2);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsRequestsBeforeThePortfolioProviderHasBeenConfigured() {
        MetsisPortfolioProperties unconfigured = new MetsisPortfolioProperties();
        MetsisPortfolioClient client = new MetsisPortfolioClient(unconfigured);

        assertThatThrownBy(() -> client.fetchPage(0, 100))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void groupsFlatLegacyRowsAndConvertsToOneBasedPaging() {
        response.set("""
            [
              {"ownerId":"48901010011","ownerName":"Mari Mets","cadastre":"78401:101:0101"},
              {"ownerId":"48901010011","ownerName":"Mari Mets","cadastre":"78401:101:0102"},
              {"ownerId":"14999999","ownerName":"Metsa OÜ","cadastre":"78401:101:0103"}
            ]
            """);
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        MetsisPortfolioClient.PageResult page = client.fetchPage(-3, 2);

        assertThat(requestedQuery.get()).isEqualTo("p=1");
        assertThat(authorization.get()).isEqualTo("portfolio-test-token");
        assertThat(page.rawRowCount()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().get(0)).extracting(MetsisPortfolioClient.PortfolioRow::ownerCode,
            MetsisPortfolioClient.PortfolioRow::ownerType, MetsisPortfolioClient.PortfolioRow::activeCadastres)
            .containsExactly("48901010011", "PERSON", java.util.List.of("78401:101:0101", "78401:101:0102"));
        assertThat(page.rows().get(1)).extracting(MetsisPortfolioClient.PortfolioRow::ownerCode,
            MetsisPortfolioClient.PortfolioRow::ownerType).containsExactly("14999999", "COMPANY");
    }

    @Test
    void rejectsMalformedFlatRowsInsteadOfApplyingAPartialSnapshot() {
        response.set("""
            [
              {"ownerId":"48901010011","ownerName":"Mari Mets","cadastre":"78401:101:0101"},
              {"ownerId":"","ownerName":"Ignored","cadastre":"78401:101:0104"}
            ]
            """);
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        assertThatThrownBy(() -> client.fetchPage(0, 100))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void keepsPagingWhenProviderReturnsFewerRowsThanOurConfiguredPageSize() {
        response.set("""
            [{"ownerId":"48901010011","ownerName":"Mari Mets","cadastre":"78401:101:0101"}]
            """);
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        MetsisPortfolioClient.PageResult shortPage = client.fetchPage(0, 1_000);

        assertThat(shortPage.rawRowCount()).isEqualTo(1);
        assertThat(shortPage.hasMore()).isTrue();
    }

    @Test
    void acceptsNestedCompatibilityPortfoliosAndReturnsAnEmptyTerminalPage() {
        response.set("""
            [{"owner":{"code":" 14999999 ","name":" Metsa OÜ ","type":"COMPANY"},
              "activeOwnings":[{"cadastreNo":"78401:101:0105"}]}]
            """);
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        MetsisPortfolioClient.PageResult nested = client.fetchPage(1, 1);
        assertThat(nested.rows()).singleElement().satisfies(row -> {
            assertThat(row.ownerCode()).isEqualTo("14999999");
            assertThat(row.ownerName()).isEqualTo("Metsa OÜ");
            assertThat(row.activeCadastres()).containsExactly("78401:101:0105");
        });
        assertThat(nested.hasMore()).isTrue();

        response.set("[]");
        MetsisPortfolioClient.PageResult empty = client.fetchPage(2, 10_001);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.rawRowCount()).isZero();
        assertThat(empty.hasMore()).isFalse();
    }

    @Test
    void rejectsMalformedNestedRowsInsteadOfApplyingAPartialSnapshot() {
        response.set("""
            [{"owner":{"code":"14999999","name":"Metsa OÜ","type":"COMPANY"},
              "activeOwnings":[{"cadastreNo":""}]}]
            """);
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        assertThatThrownBy(() -> client.fetchPage(0, 100))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void convertsUnexpectedProviderPayloadsToBadGatewayResponses() {
        response.set("{\"unexpected\":true}");
        MetsisPortfolioClient client = new MetsisPortfolioClient(properties);

        assertThatThrownBy(() -> client.fetchPage(0, 10))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
