package ee.forestiq.geospatial.cadastre;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Extracts exactly one GeoPackage from a verified cadastral snapshot ZIP. */
@Component
public class CadastreSnapshotArchive {

    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final long maximumUncompressedBytes;
    private final int maximumEntries;

    public CadastreSnapshotArchive(
        @Value("${forestiq.geospatial.cadastre.max-uncompressed-bytes:${MARU_CADASTRE_MAX_UNCOMPRESSED_BYTES:2147483648}}") long maximumUncompressedBytes,
        @Value("${forestiq.geospatial.cadastre.max-archive-entries:${MARU_CADASTRE_MAX_ARCHIVE_ENTRIES:32}}") int maximumEntries
    ) {
        if (maximumUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maximum uncompressed bytes must be positive");
        }
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximum archive entries must be positive");
        }
        this.maximumUncompressedBytes = maximumUncompressedBytes;
        this.maximumEntries = maximumEntries;
    }

    public ExtractedGeoPackage extract(CadastreSnapshotDownloader.DownloadedSnapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.path() == null) {
            throw new IllegalArgumentException("Verified snapshot is required");
        }

        Path zipPath = snapshot.path().toAbsolutePath().normalize();
        if (!Files.isRegularFile(zipPath)) {
            throw new IOException("Verified cadastral snapshot does not exist: " + zipPath);
        }

        Path workDir = Files.createTempDirectory(zipPath.getParent(), "cadastre-gpkg-");
        Path gpkgPath = workDir.resolve("cadastre.gpkg");
        int entries = 0;
        int gpkgEntries = 0;
        long gpkgBytes = 0L;

        try (InputStream fileInput = Files.newInputStream(zipPath);
             ZipInputStream zip = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > maximumEntries) {
                    throw new IOException("Cadastral snapshot contains too many archive entries");
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                String normalizedName = entry.getName().replace('\\', '/').toLowerCase();
                boolean geoPackage = normalizedName.endsWith(".gpkg");
                if (!geoPackage) {
                    zip.closeEntry();
                    continue;
                }

                gpkgEntries++;
                if (gpkgEntries > 1) {
                    throw new IOException("Cadastral snapshot must contain exactly one GeoPackage");
                }

                try (OutputStream output = Files.newOutputStream(
                    gpkgPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        if (read == 0) continue;
                        gpkgBytes = Math.addExact(gpkgBytes, read);
                        if (gpkgBytes > maximumUncompressedBytes) {
                            throw new IOException("Cadastral GeoPackage exceeds configured uncompressed size limit");
                        }
                        output.write(buffer, 0, read);
                    }
                } catch (ArithmeticException ex) {
                    throw new IOException("Cadastral GeoPackage size overflow", ex);
                }
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException ex) {
            deleteTree(workDir);
            throw ex;
        }

        if (gpkgEntries != 1 || gpkgBytes == 0L) {
            deleteTree(workDir);
            throw new IOException("Cadastral snapshot must contain one non-empty GeoPackage");
        }
        if (!hasSqliteHeader(gpkgPath)) {
            deleteTree(workDir);
            throw new IOException("Extracted cadastral artifact is not a valid SQLite/GeoPackage container");
        }

        return new ExtractedGeoPackage(workDir, gpkgPath, gpkgBytes);
    }

    private static boolean hasSqliteHeader(Path path) throws IOException {
        byte[] actual = new byte[SQLITE_HEADER.length];
        try (InputStream input = Files.newInputStream(path)) {
            int offset = 0;
            while (offset < actual.length) {
                int read = input.read(actual, offset, actual.length - offset);
                if (read < 0) return false;
                offset += read;
            }
        }
        return java.util.Arrays.equals(actual, SQLITE_HEADER);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ExtractedGeoPackage(Path workDirectory, Path path, long uncompressedBytes)
        implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(workDirectory);
        }
    }
}
