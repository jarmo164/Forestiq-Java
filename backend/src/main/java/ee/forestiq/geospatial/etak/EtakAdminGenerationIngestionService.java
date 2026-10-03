package ee.forestiq.geospatial.etak;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ee.forestiq.geospatial.GeospatialGenerationMaintenanceRepository;
import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * Per-layer atomic ingestion for ETAK and current administrative reference data.
 * Rows are written under a STAGING generation and never become reader-visible until validation
 * and the generic current-pointer publication transaction both succeed.
 */
@Service
public class EtakAdminGenerationIngestionService {
    private static final BigInteger CHECKSUM_MODULUS = BigInteger.ONE.shiftLeft(256);

    private final EtakAdminLayerManifestService manifest;
    private final EtakAdminWfsClient wfs;
    private final GeospatialSourceGenerationRepository generations;
    private final GeospatialGenerationMaintenanceRepository maintenance;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public EtakAdminGenerationIngestionService(
        EtakAdminLayerManifestService manifest,
        EtakAdminWfsClient wfs,
        GeospatialSourceGenerationRepository generations,
        GeospatialGenerationMaintenanceRepository maintenance,
        DSLContext dsl,
        ObjectMapper objectMapper
    ) {
        this.manifest = manifest;
        this.wfs = wfs;
        this.generations = generations;
        this.maintenance = maintenance;
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public IngestionResult refresh(String sourceKey,
                                   String triggerType,
                                   String actorSubject,
                                   boolean allowSchemaDrift)
        throws IOException, InterruptedException {
        EtakAdminLayerManifestService.Layer layer = manifest.require(sourceKey);
        if (!layer.enabled()) {
            throw new IllegalArgumentException("Geospatial source is disabled by manifest: " + sourceKey);
        }

        UUID runId = generations.startRun(sourceKey, triggerType);
        UUID generationId = null;
        long rowsRead = 0;
        long bytesRead = 0;
        try {
            EtakAdminWfsClient.WfsPage page = wfs.fetchPage(layer, 0);
            String observedVersion = version(page.providerTimestamp());
            LocalDate sourceDate = sourceDate(page.providerTimestamp());
            Set<String> observedFields = new HashSet<>();
            String initialFingerprint = fingerprint(layer, page.features(), observedFields);

            generationId = generations.createStagingGeneration(
                runId, sourceKey, sourceDate, observedVersion, null, null,
                initialFingerprint, layer.sourceSrid(), 3301);

            BigInteger checksumAccumulator = BigInteger.ZERO;
            long startIndex = 0;
            int pages = 0;
            while (true) {
                pages++;
                if (pages > Math.max(2, (int) Math.ceil(layer.maximumRows() / (double) wfs.pageSize()) + 2)) {
                    throw new IOException("WFS paging exceeded manifest row budget for " + sourceKey);
                }
                bytesRead = Math.addExact(bytesRead, page.bytes());
                PageWrite written = insertPage(layer, generationId, page.features(), observedFields);
                rowsRead = Math.addExact(rowsRead, written.rows());
                checksumAccumulator = checksumAccumulator.add(written.checksumContribution()).mod(CHECKSUM_MODULUS);
                if (rowsRead > layer.maximumRows()) {
                    throw new IOException("Row count exceeds manifest maximum for " + sourceKey);
                }
                if (!page.maybeMore()) break;
                startIndex += page.features().size();
                page = wfs.fetchPage(layer, startIndex);
                if (page.features().isEmpty()) break;
            }

            String finalFingerprint = fingerprint(layer, observedFields);
            validateRequiredFields(layer, observedFields);
            String previousFingerprint = maintenance.currentSchemaFingerprint(sourceKey);
            boolean schemaDrift = previousFingerprint != null && !previousFingerprint.equals(finalFingerprint);
            if (schemaDrift && !allowSchemaDrift) {
                throw new IOException("Provider schema drift detected for " + sourceKey
                    + "; manual force with audit reason is required before publication");
            }

            StageValidation validation = validateStage(layer, generationId, rowsRead);
            if (!validation.publishable()) {
                throw new IOException("Geospatial staging validation failed for " + sourceKey + ": " + validation.summary());
            }

            String checksum = checksum(checksumAccumulator, rowsRead);
            String sourceVersion = observedVersion == null ? "content:" + checksum.substring(0, 16) : observedVersion;
            dsl.execute("""
                UPDATE geospatial_source_run
                   SET rows_read = ?, rows_staged = ?, rows_inserted = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, rowsRead, rowsRead, rowsRead, runId);

            if (generations.hasAcceptedArtifactChecksum(sourceKey, checksum)) {
                maintenance.discardDuplicateStagingGeneration(generationId, runId);
                generations.recordDownloadedArtifact(runId, sourceVersion, checksum, Math.max(1, bytesRead));
                generations.finishSuccessfulCheck(runId);
                return new IngestionResult(runId, null, sourceKey, "UNCHANGED", rowsRead, checksum, sourceDate, false);
            }

            maintenance.updateStagingMetadata(
                generationId, runId, sourceDate, sourceVersion, checksum,
                Math.max(1, bytesRead), finalFingerprint);
            ObjectNode validationJson = objectMapper.createObjectNode();
            validationJson.put("manifestVersion", manifest.manifest().manifestVersion());
            validationJson.put("providerLayer", layer.providerLayer());
            validationJson.put("rowCount", rowsRead);
            validationJson.put("invalidGeometryCount", validation.invalidGeometryCount());
            validationJson.put("wrongSridCount", validation.wrongSridCount());
            validationJson.put("wrongGeometryTypeCount", validation.wrongGeometryTypeCount());
            validationJson.put("schemaDriftAccepted", schemaDrift && allowSchemaDrift);
            validationJson.put("schemaFingerprint", finalFingerprint);
            validationJson.put("businessCaveat", layer.caveat());

            generations.markValidated(generationId, rowsRead, 0, 0, validationJson.toString());
            generations.publishValidated(
                sourceKey,
                generationId,
                actorSubject,
                schemaDrift && allowSchemaDrift
                    ? "Forced publication after reviewed provider schema drift"
                    : "Validated ETAK/admin generation publication");
            dsl.execute("ANALYZE geospatial_feature");
            return new IngestionResult(runId, generationId, sourceKey, "PUBLISHED", rowsRead, checksum, sourceDate, schemaDrift);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                generations.finishUnpublishedRun(
                    runId,
                    "FAILED",
                    new GeospatialSourceGenerationRepository.RunCounters(
                        rowsRead, rowsRead, rowsRead, 0, 0, 0, 0, 0),
                    safeMessage(ex));
            } catch (RuntimeException completionFailure) {
                ex.addSuppressed(completionFailure);
            }
            throw ex;
        }
    }

    private PageWrite insertPage(EtakAdminLayerManifestService.Layer layer,
                                 UUID generationId,
                                 List<JsonNode> features,
                                 Set<String> observedFields) throws IOException {
        Query template = dsl.query("""
            INSERT INTO geospatial_feature
                (source_key, generation_id, feature_id, properties, geom)
            VALUES (?, ?, ?, ?::jsonb,
                    ST_Force2D(ST_SetSRID(ST_GeomFromGeoJSON(?), 3301)))
            """);
        BatchBindStep batch = dsl.batch(template);
        BigInteger contribution = BigInteger.ZERO;
        long rows = 0;
        for (JsonNode feature : features) {
            JsonNode rawProperties = feature.path("properties");
            JsonNode geometry = feature.path("geometry");
            if (!rawProperties.isObject() || geometry.isMissingNode() || geometry.isNull()) {
                throw new IOException("WFS feature is missing properties or geometry");
            }
            ObjectNode properties = normalizeProperties(rawProperties, observedFields);
            String identity = textIgnoreCase(properties, layer.identityField());
            if (identity == null || identity.isBlank()) {
                throw new IOException("WFS feature is missing stable identity field " + layer.identityField());
            }
            String geometryJson = geometry.toString();
            batch.bind(layer.sourceKey(), generationId, identity, properties.toString(), geometryJson);
            contribution = contribution.add(featureHash(identity, properties, geometry)).mod(CHECKSUM_MODULUS);
            rows++;
        }
        if (rows > 0) batch.execute();
        return new PageWrite(rows, contribution);
    }

    private ObjectNode normalizeProperties(JsonNode raw, Set<String> observedFields) {
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        raw.fields().forEachRemaining(entry -> sorted.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        ObjectNode normalized = objectMapper.createObjectNode();
        sorted.forEach((key, value) -> {
            observedFields.add(key);
            normalized.set(key, value);
        });
        return normalized;
    }

    private String fingerprint(EtakAdminLayerManifestService.Layer layer,
                               List<JsonNode> features,
                               Set<String> observedFields) throws IOException {
        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            if (properties.isObject()) {
                properties.fieldNames().forEachRemaining(name -> observedFields.add(name.toLowerCase(Locale.ROOT)));
            }
        }
        return fingerprint(layer, observedFields);
    }

    private static String fingerprint(EtakAdminLayerManifestService.Layer layer, Set<String> observedFields) {
        List<String> fields = new ArrayList<>(observedFields);
        fields.sort(Comparator.naturalOrder());
        return sha256(layer.geometryType().toUpperCase(Locale.ROOT) + "|" + String.join(",", fields));
    }

    private static void validateRequiredFields(EtakAdminLayerManifestService.Layer layer,
                                               Set<String> observedFields) throws IOException {
        List<String> missing = layer.requiredFields().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> !observedFields.contains(value))
            .toList();
        if (!missing.isEmpty()) {
            throw new IOException("Provider schema is missing required fields for " + layer.sourceKey() + ": " + missing);
        }
    }

    private StageValidation validateStage(EtakAdminLayerManifestService.Layer layer,
                                          UUID generationId,
                                          long rows) {
        String allowedTypes = switch (layer.geometryType().toUpperCase(Locale.ROOT)) {
            case "POLYGON" -> "('POLYGON','MULTIPOLYGON')";
            case "LINE" -> "('LINESTRING','MULTILINESTRING')";
            case "POINT" -> "('POINT','MULTIPOINT')";
            default -> throw new IllegalArgumentException("Unsupported geometry family");
        };
        Record record = dsl.fetchOne(("""
            SELECT count(*) FILTER (WHERE geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom))::bigint AS invalid_geometry_count,
                   count(*) FILTER (WHERE ST_SRID(geom) <> 3301)::bigint AS wrong_srid_count,
                   count(*) FILTER (WHERE GeometryType(geom) NOT IN %s)::bigint AS wrong_geometry_type_count
              FROM geospatial_feature
             WHERE source_key = ? AND generation_id = ?
            """).formatted(allowedTypes), layer.sourceKey(), generationId);
        long invalid = number(record, "invalid_geometry_count");
        long wrongSrid = number(record, "wrong_srid_count");
        long wrongType = number(record, "wrong_geometry_type_count");
        boolean rowRange = rows >= layer.minimumRows() && rows <= layer.maximumRows();
        return new StageValidation(rowRange && invalid == 0 && wrongSrid == 0 && wrongType == 0,
            invalid, wrongSrid, wrongType, rowRange ? "ok" : "row count outside manifest range");
    }

    private BigInteger featureHash(String identity, ObjectNode properties, JsonNode geometry) throws IOException {
        String canonical = identity + "|" + objectMapper.writeValueAsString(properties) + "|" + geometry.toString();
        return new BigInteger(1, digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String checksum(BigInteger accumulator, long rows) {
        return sha256(String.format(Locale.ROOT, "%064x|%d", accumulator, rows));
    }

    private static String sha256(String value) {
        byte[] digest = digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String textIgnoreCase(JsonNode node, String field) {
        JsonNode value = node.get(field.toLowerCase(Locale.ROOT));
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDate sourceDate(String providerTimestamp) {
        try {
            return providerTimestamp == null ? LocalDate.now(ZoneOffset.UTC) : Instant.parse(providerTimestamp).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (Exception ignored) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    private static String version(String providerTimestamp) {
        if (providerTimestamp == null || providerTimestamp.isBlank()) return null;
        return providerTimestamp.substring(0, Math.min(300, providerTimestamp.length()));
    }

    private static long number(Record record, String field) {
        if (record == null) return 0;
        Number value = record.get(field, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private static String safeMessage(Exception ex) {
        String value = ex.getMessage();
        if (value == null || value.isBlank()) value = ex.getClass().getSimpleName();
        value = value.replaceAll("[\\r\\n]+", " ").trim();
        return value.substring(0, Math.min(1800, value.length()));
    }

    private record PageWrite(long rows, BigInteger checksumContribution) { }
    private record StageValidation(boolean publishable,
                                   long invalidGeometryCount,
                                   long wrongSridCount,
                                   long wrongGeometryTypeCount,
                                   String summary) { }

    public record IngestionResult(UUID runId,
                                  UUID generationId,
                                  String sourceKey,
                                  String outcome,
                                  long rowCount,
                                  String checksum,
                                  LocalDate sourceDate,
                                  boolean schemaDriftAccepted) { }
}
