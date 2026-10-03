package ee.forestiq.geospatial.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CadastreSnapshotDownloaderTest {

    @TempDir Path tempDir;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void streamsZipToVerifiedChecksumNamedArtifact() throws Exception {
        byte[] zip = zip("cadastre.gpkg", "fake-gpkg-for-download-contract");
        server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.getResponseHeaders().add("ETag", "\"snapshot-v1\"");
            exchange.getResponseHeaders().add("Last-Modified", "Sun, 06 Sep 2026 01:00:00 GMT");
            send(exchange, 200, zip);
        });

        CadastreSnapshotDownloader downloader = downloader("/cadastre.zip", 0);
        var result = downloader.download();

        String expectedChecksum = sha256(zip);
        assertThat(result.sha256()).isEqualTo(expectedChecksum);
        assertThat(result.bytes()).isEqualTo(zip.length);
        assertThat(result.declaredBytes()).contains((long) zip.length);
        assertThat(result.etag()).contains("\"snapshot-v1\"");
        assertThat(result.lastModified()).contains("Sun, 06 Sep 2026 01:00:00 GMT");
        assertThat(result.observedSourceVersion()).isEqualTo("\"snapshot-v1\"");
        assertThat(result.createdNewFile()).isTrue();
        assertThat(result.path().getFileName().toString())
            .isEqualTo("cadastre-" + expectedChecksum.substring(0, 16) + ".zip");
        assertThat(Files.readAllBytes(result.path())).isEqualTo(zip);
        assertNoPartialFiles();
    }

    @Test
    void reusesExistingCompletedChecksumFileWithoutReplacingIt() throws Exception {
        byte[] zip = zip("cadastre.gpkg", "same-snapshot");
        server = startServer(exchange -> send(exchange, 200, zip));
        CadastreSnapshotDownloader downloader = downloader("/cadastre.zip", 0);

        var first = downloader.download();
        var firstModified = Files.getLastModifiedTime(first.path());
        Thread.sleep(10L);
        var second = downloader.download();

        assertThat(second.path()).isEqualTo(first.path());
        assertThat(second.createdNewFile()).isFalse();
        assertThat(Files.getLastModifiedTime(second.path())).isEqualTo(firstModified);
        assertNoPartialFiles();
    }

    @Test
    void rejectsProviderErrorWithoutLeavingPartialFiles() throws Exception {
        server = startServer(exchange -> send(exchange, 503, "upstream unavailable".getBytes(StandardCharsets.UTF_8)));
        CadastreSnapshotDownloader downloader = downloader("/cadastre.zip", 0);

        assertThatThrownBy(downloader::download)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("HTTP 503");

        assertNoPartialFiles();
    }

    @Test
    void rejectsNonZipArtifactAndDeletesPartialFile() throws Exception {
        byte[] invalid = "not a zip artifact".getBytes(StandardCharsets.UTF_8);
        server = startServer(exchange -> send(exchange, 200, invalid));
        CadastreSnapshotDownloader downloader = downloader("/cadastre.zip", 0);

        assertThatThrownBy(downloader::download)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not a ZIP file");

        try (var paths = Files.list(tempDir)) {
            assertThat(paths.toList()).isEmpty();
        }
    }

    @Test
    void rejectsInsufficientDiskBudgetBeforeNetworkRequest() throws Exception {
        server = startServer(exchange -> send(exchange, 200, zip("cadastre.gpkg", "unused")));
        CadastreSnapshotDownloader downloader = downloader("/cadastre.zip", Long.MAX_VALUE);

        assertThatThrownBy(downloader::download)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Insufficient free disk space");
    }

    @Test
    void refusesNonHttpsRemoteSource() {
        assertThatThrownBy(() -> new CadastreSnapshotDownloader(
            HttpClient.newHttpClient(),
            URI.create("http://example.com/cadastre.zip"),
            tempDir,
            Duration.ofSeconds(5),
            0,
            "ForestIQ-test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must use HTTPS");
    }

    private CadastreSnapshotDownloader downloader(String path, long minimumFreeBytes) {
        return new CadastreSnapshotDownloader(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
            tempDir,
            Duration.ofSeconds(10),
            minimumFreeBytes,
            "ForestIQ-test"
        );
    }

    private HttpServer startServer(ThrowingHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/cadastre.zip", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception ex) {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] zip(String filename, String contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(filename));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertNoPartialFiles() throws IOException {
        try (var paths = Files.list(tempDir)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".partial")).toList())
                .isEmpty();
        }
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
