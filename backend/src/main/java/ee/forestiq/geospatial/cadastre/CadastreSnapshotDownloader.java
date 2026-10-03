package ee.forestiq.geospatial.cadastre;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Streams the official Maa- ja Ruumiamet cadastral snapshot into local worker storage.
 *
 * <p>The importer never sees a partial file: bytes are first written to a randomly named
 * {@code .partial} file and are moved into the completed namespace only after HTTP, size,
 * ZIP-signature and SHA-256 validation succeeds.</p>
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Cadastre snapshot download remains Java-only until geospatial source cutover evidence is complete."
)
@Component
public class CadastreSnapshotDownloader {

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final byte[] ZIP_LOCAL_FILE_SIGNATURE = new byte[] {0x50, 0x4b, 0x03, 0x04};

    private final HttpClient httpClient;
    private final URI snapshotUri;
    private final Path downloadDirectory;
    private final Duration requestTimeout;
    private final long minimumFreeBytes;
    private final String userAgent;

    @Autowired
    public CadastreSnapshotDownloader(
        @Value("${forestiq.geospatial.cadastre.snapshot-url:${MARU_CADASTRE_SNAPSHOT_URL:https://s3.pilw.io/rp-kemit-kataster/ANDMED/Eesti_KATASTER_GPKG.zip}}") String snapshotUrl,
        @Value("${forestiq.geospatial.cadastre.download-dir:${GEOSPATIAL_DOWNLOAD_DIR:./var/geospatial/cadastre}}") String downloadDirectory,
        @Value("${forestiq.geospatial.cadastre.connect-timeout-seconds:${MARU_CADASTRE_CONNECT_TIMEOUT_SECONDS:15}}") long connectTimeoutSeconds,
        @Value("${forestiq.geospatial.cadastre.request-timeout-seconds:${MARU_CADASTRE_REQUEST_TIMEOUT_SECONDS:900}}") long requestTimeoutSeconds,
        @Value("${forestiq.geospatial.cadastre.minimum-free-bytes:${GEOSPATIAL_MINIMUM_FREE_BYTES:536870912}}") long minimumFreeBytes,
        @Value("${forestiq.geospatial.cadastre.user-agent:${MARU_CADASTRE_USER_AGENT:ForestIQ/2.0}}") String userAgent
    ) {
        this(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(connectTimeoutSeconds, "connect timeout")))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            URI.create(snapshotUrl),
            Path.of(downloadDirectory),
            Duration.ofSeconds(positive(requestTimeoutSeconds, "request timeout")),
            nonNegative(minimumFreeBytes, "minimum free bytes"),
            userAgent
        );
    }

    CadastreSnapshotDownloader(HttpClient httpClient,
                               URI snapshotUri,
                               Path downloadDirectory,
                               Duration requestTimeout,
                               long minimumFreeBytes,
                               String userAgent) {
        this.httpClient = httpClient;
        this.snapshotUri = requireHttpsOrLoopback(snapshotUri);
        this.downloadDirectory = downloadDirectory.toAbsolutePath().normalize();
        this.requestTimeout = requestTimeout;
        this.minimumFreeBytes = nonNegative(minimumFreeBytes, "minimum free bytes");
        this.userAgent = userAgent == null || userAgent.isBlank() ? "ForestIQ/2.0" : userAgent.trim();
    }

    public DownloadedSnapshot download() throws IOException, InterruptedException {
        Files.createDirectories(downloadDirectory);
        ensureFreeSpace(null);

        HttpRequest request = HttpRequest.newBuilder(snapshotUri)
            .GET()
            .timeout(requestTimeout)
            .header("Accept", "application/zip,application/octet-stream,*/*;q=0.1")
            .header("User-Agent", userAgent)
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream ignored = response.body()) {
                // Close the provider body without retaining or logging it.
            }
            throw new IOException("Cadastral snapshot download failed with HTTP " + status);
        }

        URI finalUri = requireHttpsOrLoopback(response.uri());
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredLength == 0L) {
            closeQuietly(response.body());
            throw new IOException("Cadastral snapshot provider returned an empty artifact");
        }
        try {
            ensureFreeSpace(declaredLength >= 0 ? declaredLength : null);
        } catch (IOException | RuntimeException ex) {
            closeQuietly(response.body());
            throw ex;
        }

        String etag = response.headers().firstValue("ETag").orElse(null);
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        Instant startedAt = Instant.now();

        Path partial = Files.createTempFile(downloadDirectory, "cadastre-", ".zip.partial");
        long bytesWritten = 0L;
        MessageDigest digest = sha256();
        byte[] firstBytes = new byte[ZIP_LOCAL_FILE_SIGNATURE.length];
        int capturedFirstBytes = 0;

        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(
                 partial, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);

                if (capturedFirstBytes < firstBytes.length) {
                    int copy = Math.min(read, firstBytes.length - capturedFirstBytes);
                    System.arraycopy(buffer, 0, firstBytes, capturedFirstBytes, copy);
                    capturedFirstBytes += copy;
                }

                bytesWritten += read;
                if ((bytesWritten & ((64L * 1024L * 1024L) - 1L)) < read) {
                    ensureFreeSpace(null);
                }
            }
            output.flush();

            if (bytesWritten == 0L) {
                throw new IOException("Cadastral snapshot provider returned an empty artifact");
            }
            if (declaredLength >= 0 && bytesWritten != declaredLength) {
                throw new IOException(
                    "Cadastral snapshot size mismatch: expected " + declaredLength + " bytes but received " + bytesWritten);
            }
            if (capturedFirstBytes < ZIP_LOCAL_FILE_SIGNATURE.length || !isZipSignature(firstBytes)) {
                throw new IOException("Cadastral snapshot artifact is not a ZIP file");
            }

            String checksum = HexFormat.of().formatHex(digest.digest());
            Path completed = downloadDirectory.resolve("cadastre-" + checksum.substring(0, 16) + ".zip");
            boolean createdNewFile;
            if (Files.exists(completed)) {
                Files.delete(partial);
                createdNewFile = false;
            } else {
                moveAtomicallyWhenSupported(partial, completed);
                createdNewFile = true;
            }

            return new DownloadedSnapshot(
                completed,
                finalUri,
                checksum,
                bytesWritten,
                declaredLength >= 0 ? Optional.of(declaredLength) : Optional.empty(),
                optional(etag),
                optional(lastModified),
                optional(contentType),
                createdNewFile,
                startedAt,
                Instant.now()
            );
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(partial);
            throw ex;
        }
    }

    public void deleteArtifact(DownloadedSnapshot snapshot) throws IOException {
        if (snapshot == null) return;
        Path candidate = snapshot.path().toAbsolutePath().normalize();
        if (!candidate.getParent().equals(downloadDirectory)) {
            throw new IOException("Refusing to delete artifact outside configured cadastral download directory");
        }
        Files.deleteIfExists(candidate);
    }

    private void ensureFreeSpace(Long expectedArtifactBytes) throws IOException {
        FileStore store = Files.getFileStore(downloadDirectory);
        long required = minimumFreeBytes;
        if (expectedArtifactBytes != null && expectedArtifactBytes > 0) {
            try {
                required = Math.addExact(required, expectedArtifactBytes);
            } catch (ArithmeticException ex) {
                throw new IOException("Configured disk-space budget overflows 64-bit byte count", ex);
            }
        }
        long available = store.getUsableSpace();
        if (available < required) {
            throw new IOException(
                "Insufficient free disk space for cadastral snapshot: available=" + available + ", required=" + required);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void moveAtomicallyWhenSupported(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
    }

    private static boolean isZipSignature(byte[] bytes) {
        for (int i = 0; i < ZIP_LOCAL_FILE_SIGNATURE.length; i++) {
            if (bytes[i] != ZIP_LOCAL_FILE_SIGNATURE[i]) return false;
        }
        return true;
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static URI requireHttpsOrLoopback(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Cadastre snapshot URL must be an absolute HTTP(S) URL");
        }
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("Cadastre snapshot URL must use HTTPS (HTTP is allowed only for loopback tests)");
        }
        return uri;
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    private static long nonNegative(long value, String label) {
        if (value < 0) throw new IllegalArgumentException(label + " must not be negative");
        return value;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Nothing useful to do while handling an invalid provider response.
        }
    }

    public record DownloadedSnapshot(
        Path path,
        URI sourceUri,
        String sha256,
        long bytes,
        Optional<Long> declaredBytes,
        Optional<String> etag,
        Optional<String> lastModified,
        Optional<String> contentType,
        boolean createdNewFile,
        Instant startedAt,
        Instant completedAt
    ) {
        public String observedSourceVersion() {
            return etag.or(() -> lastModified).orElse(null);
        }
    }
}
