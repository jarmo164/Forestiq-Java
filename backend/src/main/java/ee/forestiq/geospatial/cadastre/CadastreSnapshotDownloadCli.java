package ee.forestiq.geospatial.cadastre;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Operator entry point for obtaining one verified Maa- ja Ruumiamet nationwide
 * cadastral snapshot without starting the ForestIQ API or requiring a database.
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Cadastre snapshot download remains Java-only until geospatial source cutover evidence is complete."
)
public final class CadastreSnapshotDownloadCli {

    private static final String DEFAULT_SNAPSHOT_URL =
        "https://s3.pilw.io/rp-kemit-kataster/ANDMED/Eesti_KATASTER_GPKG.zip";

    private CadastreSnapshotDownloadCli() {
    }

    public static void main(String[] args) throws Exception {
        String snapshotUrl = env("MARU_CADASTRE_SNAPSHOT_URL", DEFAULT_SNAPSHOT_URL);
        String downloadDir = env("GEOSPATIAL_DOWNLOAD_DIR", "./var/geospatial/cadastre");
        long connectTimeoutSeconds = positiveLong(
            env("MARU_CADASTRE_CONNECT_TIMEOUT_SECONDS", "15"),
            "MARU_CADASTRE_CONNECT_TIMEOUT_SECONDS");
        long requestTimeoutSeconds = positiveLong(
            env("MARU_CADASTRE_REQUEST_TIMEOUT_SECONDS", "900"),
            "MARU_CADASTRE_REQUEST_TIMEOUT_SECONDS");
        long minimumFreeBytes = nonNegativeLong(
            env("GEOSPATIAL_MINIMUM_FREE_BYTES", "536870912"),
            "GEOSPATIAL_MINIMUM_FREE_BYTES");
        String userAgent = env("MARU_CADASTRE_USER_AGENT", "ForestIQ/2.0");

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        CadastreSnapshotDownloader downloader = new CadastreSnapshotDownloader(
            client,
            URI.create(snapshotUrl),
            Path.of(downloadDir),
            Duration.ofSeconds(requestTimeoutSeconds),
            minimumFreeBytes,
            userAgent);

        var snapshot = downloader.download();
        System.out.println("path=" + snapshot.path());
        System.out.println("sourceUrl=" + snapshot.sourceUri());
        System.out.println("sha256=" + snapshot.sha256());
        System.out.println("bytes=" + snapshot.bytes());
        if (snapshot.observedSourceVersion() != null) {
            System.out.println("sourceVersion=" + snapshot.observedSourceVersion());
        }
        System.out.println("startedAt=" + snapshot.startedAt());
        System.out.println("completedAt=" + snapshot.completedAt());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long positiveLong(String value, String name) {
        long parsed = parseLong(value, name);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static long nonNegativeLong(String value, String name) {
        long parsed = parseLong(value, name);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return parsed;
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be an integer", ex);
        }
    }
}
