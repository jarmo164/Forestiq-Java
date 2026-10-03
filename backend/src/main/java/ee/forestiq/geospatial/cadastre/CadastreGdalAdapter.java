package ee.forestiq.geospatial.cadastre;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Boundary to GDAL for authoritative GeoPackage inspection and bounded-memory import.
 * Commands are built as argument lists; no provider-controlled value is evaluated by a shell.
 */
@Component
public class CadastreGdalAdapter {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Pattern WKT_EPSG_ID = Pattern.compile(
        "(?:ID\\[\\\"EPSG\\\",\\s*(\\d+)\\]|AUTHORITY\\[\\\"EPSG\\\",\\s*\\\"(\\d+)\\\"\\])",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> DEFAULT_REQUIRED_FIELDS = Set.of(
        "tunnus", "hkood", "mk_nimi", "ov_nimi", "ay_nimi", "oiguslik_alus", "eksport");

    private final ObjectMapper objectMapper;
    private final String ogrinfoExecutable;
    private final String ogr2ogrExecutable;
    private final Duration inspectTimeout;
    private final Duration importTimeout;
    private final String configuredLayer;
    private final String codeField;
    private final Set<Integer> allowedSourceSrids;
    private final String jdbcUrl;
    private final String databaseUsername;
    private final String databasePassword;

    public CadastreGdalAdapter(
        ObjectMapper objectMapper,
        @Value("${forestiq.geospatial.gdal.ogrinfo:${GDAL_OGRINFO:ogrinfo}}") String ogrinfoExecutable,
        @Value("${forestiq.geospatial.gdal.ogr2ogr:${GDAL_OGR2OGR:ogr2ogr}}") String ogr2ogrExecutable,
        @Value("${forestiq.geospatial.gdal.inspect-timeout-seconds:${GDAL_INSPECT_TIMEOUT_SECONDS:60}}") long inspectTimeoutSeconds,
        @Value("${forestiq.geospatial.gdal.import-timeout-seconds:${GDAL_IMPORT_TIMEOUT_SECONDS:3600}}") long importTimeoutSeconds,
        @Value("${forestiq.geospatial.cadastre.layer:${MARU_CADASTRE_LAYER:}}") String configuredLayer,
        @Value("${forestiq.geospatial.cadastre.code-field:${MARU_CADASTRE_CODE_FIELD:tunnus}}") String codeField,
        @Value("${forestiq.geospatial.cadastre.allowed-source-srids:${MARU_CADASTRE_ALLOWED_SOURCE_SRIDS:3301}}") String allowedSourceSrids,
        @Value("${spring.datasource.url}") String jdbcUrl,
        @Value("${spring.datasource.username}") String databaseUsername,
        @Value("${spring.datasource.password}") String databasePassword
    ) {
        this.objectMapper = objectMapper;
        this.ogrinfoExecutable = requireExecutable(ogrinfoExecutable, "ogrinfo");
        this.ogr2ogrExecutable = requireExecutable(ogr2ogrExecutable, "ogr2ogr");
        this.inspectTimeout = Duration.ofSeconds(positive(inspectTimeoutSeconds, "GDAL inspect timeout"));
        this.importTimeout = Duration.ofSeconds(positive(importTimeoutSeconds, "GDAL import timeout"));
        this.configuredLayer = blankToNull(configuredLayer);
        this.codeField = requireSafeIdentifier(codeField, "cadastral code field").toLowerCase(Locale.ROOT);
        this.allowedSourceSrids = parseAllowedSrids(allowedSourceSrids);
        this.jdbcUrl = jdbcUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
    }

    public GeoPackageManifest inspect(Path gpkg) throws IOException, InterruptedException {
        CommandResult inventory = run(
            List.of(ogrinfoExecutable, "-ro", "-so", "-al", "-json", gpkg.toString()),
            Map.of(), inspectTimeout, 4 * 1024 * 1024);
        if (inventory.exitCode() != 0) {
            throw new IOException("ogrinfo failed to inspect cadastral GeoPackage: " + inventory.output());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(inventory.output());
        } catch (Exception ex) {
            throw new IOException("ogrinfo returned invalid JSON metadata", ex);
        }
        JsonNode layers = root.path("layers");
        if (!layers.isArray() || layers.isEmpty()) {
            throw new IOException("Cadastral GeoPackage contains no vector layers");
        }

        JsonNode selected = null;
        for (JsonNode layer : layers) {
            String name = layer.path("name").asText("");
            if (configuredLayer != null && !configuredLayer.equals(name)) continue;
            if (hasRequiredFields(layer) && hasPolygonGeometry(layer)) {
                if (selected != null && configuredLayer == null) {
                    throw new IOException("Multiple cadastral-looking polygon layers found; configure MARU_CADASTRE_LAYER explicitly");
                }
                selected = layer;
            }
        }
        if (selected == null) {
            throw new IOException(
                "No cadastral polygon layer with the required 2026 Maa- ja Ruumiamet schema was found");
        }

        String layerName = selected.path("name").asText();
        requireSafeIdentifier(layerName, "cadastral layer");
        String geometryField = geometryFieldName(selected);
        requireSafeIdentifier(geometryField, "cadastral geometry field");

        CommandResult summary = run(
            List.of(ogrinfoExecutable, "-ro", "-so", gpkg.toString(), layerName),
            Map.of(), inspectTimeout, 2 * 1024 * 1024);
        if (summary.exitCode() != 0) {
            throw new IOException("ogrinfo failed to inspect cadastral layer CRS: " + summary.output());
        }
        int sourceSrid = extractSourceSrid(summary.output());
        if (!allowedSourceSrids.contains(sourceSrid)) {
            throw new IOException(
                "Cadastral GeoPackage source CRS EPSG:" + sourceSrid
                    + " is not approved; allowed=" + allowedSourceSrids);
        }

        long featureCount = selected.path("featureCount").asLong(-1L);
        String schemaFingerprint = sha256(
            selected.toString() + "\nsourceSrid=" + sourceSrid + "\ncodeField=" + codeField);
        return new GeoPackageManifest(
            layerName,
            geometryField,
            codeField,
            sourceSrid,
            featureCount,
            schemaFingerprint,
            fieldNames(selected));
    }

    /** Imports one provider layer to a controlled raw staging table normalized to EPSG:3301. */
    public String importToStage(Path gpkg, GeoPackageManifest manifest, UUID runId)
        throws IOException, InterruptedException {
        String stageTable = stageTable(runId);
        PostgresTarget target = PostgresTarget.fromJdbc(jdbcUrl, databaseUsername, databasePassword);

        List<String> command = new ArrayList<>(List.of(
            ogr2ogrExecutable,
            "-f", "PostgreSQL",
            target.gdalDatasetName(),
            gpkg.toString(),
            manifest.layerName(),
            "-nln", stageTable,
            "-overwrite",
            "-lco", "GEOMETRY_NAME=geom",
            "-lco", "FID=ogr_fid",
            "-nlt", "PROMOTE_TO_MULTI",
            "-dim", "XY",
            "-t_srs", "EPSG:3301",
            "-gt", "65536"
        ));

        CommandResult result = run(command, target.environment(), importTimeout, 512 * 1024);
        if (result.exitCode() != 0) {
            throw new IOException("ogr2ogr cadastral staging import failed: " + result.output());
        }
        return stageTable;
    }

    static String stageTable(UUID runId) {
        return "cadastre_stage_" + runId.toString().replace("-", "");
    }

    private boolean hasRequiredFields(JsonNode layer) {
        Set<String> names = fieldNames(layer);
        return names.contains(codeField) && names.containsAll(DEFAULT_REQUIRED_FIELDS);
    }

    private static Set<String> fieldNames(JsonNode layer) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode field : layer.path("fields")) {
            String name = field.path("name").asText("").toLowerCase(Locale.ROOT);
            if (!name.isBlank()) names.add(name);
        }
        return Set.copyOf(names);
    }

    private static boolean hasPolygonGeometry(JsonNode layer) {
        for (JsonNode geometryField : layer.path("geometryFields")) {
            String type = geometryField.path("type").asText("").toLowerCase(Locale.ROOT);
            if (type.contains("polygon") || "geometry".equals(type)) return true;
        }
        return false;
    }

    private static String geometryFieldName(JsonNode layer) throws IOException {
        for (JsonNode geometryField : layer.path("geometryFields")) {
            String type = geometryField.path("type").asText("").toLowerCase(Locale.ROOT);
            if (type.contains("polygon") || "geometry".equals(type)) {
                String name = geometryField.path("name").asText("");
                if (!name.isBlank()) return name;
            }
        }
        throw new IOException("Cadastral layer has no named polygon geometry field");
    }

    private int extractSourceSrid(String ogrinfoText) throws IOException {
        Matcher matcher = WKT_EPSG_ID.matcher(ogrinfoText);
        Integer last = null;
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            last = Integer.parseInt(value);
        }
        if (last == null || last <= 0) {
            throw new IOException("Cadastral layer CRS has no resolvable EPSG identifier");
        }
        return last;
    }

    private static Set<Integer> parseAllowedSrids(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (part.isBlank()) continue;
            int srid;
            try {
                srid = Integer.parseInt(part.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid approved cadastral source SRID: " + part, ex);
            }
            if (srid <= 0) throw new IllegalArgumentException("Approved source SRID must be positive");
            result.add(srid);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("At least one approved cadastral source SRID is required");
        return Set.copyOf(result);
    }

    private static CommandResult run(List<String> command,
                                     Map<String, String> environment,
                                     Duration timeout,
                                     int captureLimitBytes) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();

        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(captureLimitBytes, 64 * 1024));
        Thread drain = Thread.ofVirtual().name("forestiq-gdal-output").start(() -> {
            byte[] buffer = new byte[8192];
            try (var input = process.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (captured.size() < captureLimitBytes) {
                        int keep = Math.min(read, captureLimitBytes - captured.size());
                        captured.write(buffer, 0, keep);
                    }
                }
            } catch (IOException ignored) {
                // Process exit handling below reports the command failure.
            }
        });

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            drain.join(Duration.ofSeconds(5));
            throw new IOException("GDAL command exceeded timeout " + timeout.toSeconds() + "s");
        }
        drain.join(Duration.ofSeconds(5));
        String output = captured.toString(StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), bounded(output, captureLimitBytes));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String bounded(String value, int maxBytes) {
        if (value == null) return "";
        String clean = value.replaceAll("(?i)(password|authorization|token|secret)\\s*[:=]\\s*[^,;\\s]+", "$1=[redacted]");
        if (clean.length() <= maxBytes) return clean.trim();
        return clean.substring(0, maxBytes).trim() + " …[truncated]";
    }

    private static String requireSafeIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a safe SQL/GDAL identifier: " + value);
        }
        return value;
    }

    private static String requireExecutable(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " executable is required");
        String trimmed = value.trim();
        if (trimmed.contains("\n") || trimmed.contains("\r")) {
            throw new IllegalArgumentException(label + " executable contains a line break");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    public record GeoPackageManifest(
        String layerName,
        String geometryField,
        String codeField,
        int sourceSrid,
        long providerFeatureCount,
        String schemaFingerprint,
        Set<String> fields
    ) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record PostgresTarget(String database, String host, int port, String username, String password, String sslMode) {
        static PostgresTarget fromJdbc(String jdbcUrl, String username, String password) {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("GeoPackage worker requires a jdbc:postgresql:// datasource URL");
            }
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (uri.getHost() == null || database.isBlank() || database.contains("'") || database.contains("\\")) {
                throw new IllegalArgumentException("PostgreSQL datasource URL is not suitable for GDAL import");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String sslMode = queryValue(uri.getRawQuery(), "sslmode");
            return new PostgresTarget(database, uri.getHost(), port, username, password, sslMode);
        }

        String gdalDatasetName() {
            return "PG:dbname='" + database + "'";
        }

        Map<String, String> environment() {
            java.util.HashMap<String, String> env = new java.util.HashMap<>();
            env.put("PGHOST", host);
            env.put("PGPORT", Integer.toString(port));
            env.put("PGDATABASE", database);
            if (username != null && !username.isBlank()) env.put("PGUSER", username);
            if (password != null && !password.isBlank()) env.put("PGPASSWORD", password);
            if (sslMode != null && !sslMode.isBlank()) env.put("PGSSLMODE", sslMode);
            return Map.copyOf(env);
        }

        private static String queryValue(String query, String key) {
            if (query == null || query.isBlank()) return null;
            return Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equalsIgnoreCase(key))
                .map(parts -> java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
        }
    }
}
