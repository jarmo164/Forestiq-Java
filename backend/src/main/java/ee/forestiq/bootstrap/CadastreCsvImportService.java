package ee.forestiq.bootstrap;

import static ee.forestiq.config.PostgresCopySupport.copyIn;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Streams a ForestIQ-formatted nationwide cadastre CSV into the shared public dataset snapshot.
 * The previously published snapshot stays active until the entire CSV has been parsed, staged,
 * validated and projected successfully.
 */
@Service
public class CadastreCsvImportService {
    private static final String SOURCE_KEY = PublicDatasetBootstrapService.MAAAMET_CADASTRE;
    private static final String TYPE_NAME = "kataster:ky_kehtiv";
    private static final int COPY_CHUNK_SIZE = 2_000;
    private static final long MAX_FILE_BYTES = 1_024L * 1_024L * 1_024L;
    private static final long MAX_ROWS = 2_000_000L;
    private static final String STAGE_COPY_SQL = """
        COPY cadastre_import_stage
            (run_id, row_number, feature_key, source_id, cadastral_code, properties, geometry, raw_feature)
        FROM STDIN WITH (FORMAT csv)
        """;

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final BootstrapProperties bootstrapProperties;

    public CadastreCsvImportService(DSLContext dsl, ObjectMapper objectMapper,
                                    BootstrapProperties bootstrapProperties) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.bootstrapProperties = bootstrapProperties;
    }

    public ImportResult importCsv(UUID organizationId, InputStream source, String originalFilename,
                                  long declaredContentLength) {
        if (declaredContentLength > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Katastri CSV võib olla kuni 1 GB.");
        }

        String filename = safeFilename(originalFilename);
        UUID snapshotId = startSnapshot(filename);
        long rows = 0L;
        long geometryRows = 0L;
        int batches = 0;
        LimitedInputStream limited = new LimitedInputStream(source, MAX_FILE_BYTES);

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(limited, StandardCharsets.UTF_8), 128 * 1024)) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw badRequest("CSV-failis puudub päiserida.");
            }
            char delimiter = detectDelimiter(headerLine);
            List<String> headers = parseRecord(headerLine, delimiter);
            if (!headers.isEmpty() && headers.getFirst().startsWith("\uFEFF")) {
                headers.set(0, headers.getFirst().substring(1));
            }
            Map<String, Integer> columns = headerMap(headers);
            requireColumn(columns, "cadastral_code");

            CsvRecordReader csv = new CsvRecordReader(reader, delimiter);
            List<SnapshotRow> batch = new ArrayList<>(COPY_CHUNK_SIZE);
            List<String> values;
            long logicalRow = 1L;

            while ((values = csv.next()) != null) {
                logicalRow++;
                if (values.stream().allMatch(String::isBlank)) continue;
                if (values.size() != headers.size()) {
                    throw badRequest("CSV real " + logicalRow + " on " + values.size()
                        + " veergu, kuid päises on " + headers.size() + ".");
                }
                if (rows >= MAX_ROWS) {
                    throw badRequest("Katastri CSV-s võib olla kuni " + MAX_ROWS + " andmerida.");
                }

                SnapshotRow row = toSnapshotRow(columns, values, logicalRow);
                batch.add(row);
                rows++;
                if (row.geometryJson() != null) geometryRows++;

                if (batch.size() >= COPY_CHUNK_SIZE) {
                    batches++;
                    flush(snapshotId, batch, rows, batches);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                batches++;
                flush(snapshotId, batch, rows, batches);
                batch.clear();
            }
            if (rows == 0L) throw badRequest("CSV-failis ei ole katastri andmeridu.");

            long staged = validateAndPromoteStage(snapshotId);
            if (staged != rows) {
                throw badRequest("Katastri stagingu ridade arv ei vasta loetud ridade arvule: loeti " + rows
                    + " rida, stagingus on " + staged + ".");
            }

            int projected = publishAndProject(snapshotId, organizationId, rows, geometryRows, limited.bytesRead());
            return new ImportResult(snapshotId, rows, geometryRows, projected, limited.bytesRead(), filename);
        } catch (PayloadTooLargeIOException exception) {
            resetFailedSnapshot(snapshotId, exception.getMessage());
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, exception.getMessage(), exception);
        } catch (ResponseStatusException exception) {
            resetFailedSnapshot(snapshotId, exception.getReason());
            throw exception;
        } catch (IOException exception) {
            resetFailedSnapshot(snapshotId, exception.getMessage());
            throw badRequest("Katastri CSV lugemine katkes: " + safeMessage(exception));
        } catch (RuntimeException exception) {
            resetFailedSnapshot(snapshotId, exception.getMessage());
            throw exception;
        }
    }

    private UUID startSnapshot(String filename) {
        Object activeValue = dsl.fetchValue("""
            SELECT id FROM external_dataset_snapshot_run
             WHERE source_key = ? AND status = 'RUNNING'
             ORDER BY started_at DESC LIMIT 1
            """, SOURCE_KEY);
        UUID active = activeValue instanceof UUID uuid ? uuid : null;
        if (active != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Katastri täissünkroonimine või CSV import juba töötab.");
        }

        UUID id = UUID.randomUUID();
        try {
            dsl.execute("""
                INSERT INTO external_dataset_snapshot_run
                    (id, source_key, source_url, type_name, status, next_start_index,
                     pages_processed, records_staged, expected_records)
                VALUES (?, ?, ?, ?, 'RUNNING', 0, 0, 0, NULL)
                """, id, SOURCE_KEY, "manual-csv:" + filename, TYPE_NAME);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Katastri täissünkroonimine või CSV import juba töötab.", exception);
        }
        return id;
    }

    private SnapshotRow toSnapshotRow(Map<String, Integer> columns, List<String> values, long rowNumber) {
        String cadastralCode = cell(columns, values, "cadastral_code");
        if (cadastralCode.isBlank()) throw badRequest("CSV real " + rowNumber + " puudub cadastral_code.");
        if (cadastralCode.length() > 32) throw badRequest("CSV real " + rowNumber + " katastritunnus on liiga pikk.");

        String sourceId = cell(columns, values, "source_id");
        if (sourceId.isBlank()) sourceId = cadastralCode;
        if (sourceId.length() > 300) throw badRequest("CSV real " + rowNumber + " source_id on liiga pikk.");

        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("tunnus", cadastralCode);
        putText(properties, "l_aadress", firstNonBlank(
            cell(columns, values, "address"), cell(columns, values, "name")));
        putText(properties, "mk_nimi", cell(columns, values, "county"));
        putText(properties, "ov_nimi", cell(columns, values, "municipality"));
        putText(properties, "kinnistu", cell(columns, values, "reg_nr"));
        putText(properties, "omvorm", cell(columns, values, "parcel_type"));
        putSquareMetres(properties, "pindala", cell(columns, values, "area_hectares"), rowNumber);
        putSquareMetres(properties, "mets", cell(columns, values, "forest_area"), rowNumber);
        putSquareMetres(properties, "haritav", cell(columns, values, "arable_area"), rowNumber);
        putSquareMetres(properties, "ouemaa", cell(columns, values, "yard_area"), rowNumber);
        putSquareMetres(properties, "muumaa", cell(columns, values, "other_area"), rowNumber);

        String geometryJson = null;
        String geometry = cell(columns, values, "boundary_geojson");
        if (!geometry.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(geometry);
                if (parsed == null || !parsed.isObject() || parsed.path("type").asText().isBlank()
                    || !parsed.has("coordinates")) {
                    throw badRequest("CSV real " + rowNumber + " boundary_geojson ei ole korrektne GeoJSON geomeetria.");
                }
                geometryJson = parsed.toString();
            } catch (IOException exception) {
                throw badRequest("CSV real " + rowNumber + " boundary_geojson ei ole korrektne JSON.");
            }
        }

        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("source", "FORESTIQ_CADASTRE_CSV");
        raw.put("id", sourceId);
        raw.put("cadastral_code", cadastralCode);

        return new SnapshotRow(cadastralCode, sourceId, properties.toString(), geometryJson, raw.toString());
    }

    private void flush(UUID snapshotId, List<SnapshotRow> rows, long rowsRead, int batches) {
        long firstRowNumber = rowsRead - rows.size() + 1;
        StringBuilder csv = new StringBuilder(Math.max(16_384, rows.size() * 512));
        for (int i = 0; i < rows.size(); i++) {
            SnapshotRow row = rows.get(i);
            appendCsv(csv, snapshotId.toString());
            appendCsv(csv, Long.toString(firstRowNumber + i));
            appendCsv(csv, row.cadastralCode());
            appendCsv(csv, row.sourceId());
            appendCsv(csv, row.cadastralCode());
            appendCsv(csv, row.propertiesJson());
            appendCsv(csv, row.geometryJson());
            appendCsv(csv, row.rawFeatureJson());
            csv.append('\n');
        }

        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            long copied = copyIn(tx, STAGE_COPY_SQL, new StringReader(csv.toString()));
            if (copied != rows.size()) {
                throw new IllegalStateException("PostgreSQL COPY staged " + copied + " rows, expected " + rows.size());
            }
            tx.execute("""
                UPDATE external_dataset_snapshot_run
                   SET next_start_index = ?, pages_processed = ?, records_staged = ?,
                       updated_at = CURRENT_TIMESTAMP, last_error = NULL
                 WHERE id = ?
                """, rowsRead, batches, rowsRead, snapshotId);
        });
    }

    private long validateAndPromoteStage(UUID snapshotId) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            long staged = ((Number) tx.fetchValue(
                "SELECT count(*) FROM cadastre_import_stage WHERE run_id = ?", snapshotId)).longValue();
            long unique = ((Number) tx.fetchValue(
                "SELECT count(DISTINCT feature_key) FROM cadastre_import_stage WHERE run_id = ?", snapshotId)).longValue();
            if (staged != unique) {
                throw badRequest("CSV sisaldab korduvaid katastritunnuseid: loeti " + staged
                    + " rida, kuid unikaalseid katastriüksusi on " + unique + ".");
            }

            tx.execute("""
                INSERT INTO external_dataset_feature
                    (source_key, snapshot_id, feature_key, source_id, cadastral_code,
                     properties, geometry, raw_feature)
                SELECT ?, run_id, feature_key, source_id, cadastral_code,
                       properties, geometry, raw_feature
                  FROM cadastre_import_stage
                 WHERE run_id = ?
                ON CONFLICT (source_key, snapshot_id, feature_key) DO UPDATE SET
                    source_id = EXCLUDED.source_id,
                    cadastral_code = EXCLUDED.cadastral_code,
                    properties = EXCLUDED.properties,
                    geometry = EXCLUDED.geometry,
                    raw_feature = EXCLUDED.raw_feature,
                    fetched_at = CURRENT_TIMESTAMP
                """, SOURCE_KEY, snapshotId);
            return staged;
        });
    }

    private int publishAndProject(UUID snapshotId, UUID organizationId, long rows,
                                  long geometryRows, long bytesRead) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();

            int projected = tx.execute("""
                UPDATE land_parcel p
                   SET name = COALESCE(NULLIF(f.properties->>'l_aadress', ''), p.name),
                       address = COALESCE(NULLIF(f.properties->>'l_aadress', ''), p.address),
                       county = COALESCE(NULLIF(f.properties->>'mk_nimi', ''), p.county),
                       municipality = COALESCE(NULLIF(f.properties->>'ov_nimi', ''), p.municipality),
                       reg_nr = COALESCE(NULLIF(f.properties->>'kinnistu', ''), p.reg_nr),
                       parcel_type = COALESCE(NULLIF(f.properties->>'omvorm', ''), p.parcel_type),
                       area_hectares = COALESCE(
                           CASE WHEN COALESCE(f.properties->>'pindala', '') ~ '^[0-9]+([.][0-9]+)?$'
                                THEN round((f.properties->>'pindala')::numeric / 10000, 4) END,
                           p.area_hectares),
                       forest_area = CASE WHEN COALESCE(f.properties->>'mets', '') ~ '^[0-9]+([.][0-9]+)?$'
                                THEN round((f.properties->>'mets')::numeric / 10000, 4) ELSE p.forest_area END,
                       arable_area = CASE WHEN COALESCE(f.properties->>'haritav', '') ~ '^[0-9]+([.][0-9]+)?$'
                                THEN round((f.properties->>'haritav')::numeric / 10000, 4) ELSE p.arable_area END,
                       yard_area = CASE WHEN COALESCE(f.properties->>'ouemaa', '') ~ '^[0-9]+([.][0-9]+)?$'
                                THEN round((f.properties->>'ouemaa')::numeric / 10000, 4) ELSE p.yard_area END,
                       other_area = CASE WHEN COALESCE(f.properties->>'muumaa', '') ~ '^[0-9]+([.][0-9]+)?$'
                                THEN round((f.properties->>'muumaa')::numeric / 10000, 4) ELSE p.other_area END,
                       boundary = CASE WHEN f.geometry IS NULL THEN p.boundary
                           ELSE ST_SetSRID(ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(f.geometry::text))), 4326) END,
                       updated_at = CURRENT_TIMESTAMP
                  FROM external_dataset_feature f
                 WHERE f.source_key = ? AND f.snapshot_id = ?
                   AND p.organization_id = ?
                   AND p.cadastral_code = f.cadastral_code
                """, SOURCE_KEY, snapshotId, organizationId);

            tx.execute("""
                INSERT INTO external_dataset_current (source_key, snapshot_id, published_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (source_key) DO UPDATE SET
                    snapshot_id = EXCLUDED.snapshot_id,
                    published_at = EXCLUDED.published_at
                """, SOURCE_KEY, snapshotId);

            tx.execute("""
                UPDATE external_dataset_snapshot_run
                   SET status = 'COMPLETE', next_start_index = ?, records_staged = ?,
                       expected_records = ?, records_projected = ?, geometry_records = ?,
                       bytes_read = ?, records_rejected = 0,
                       completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP, last_error = NULL
                 WHERE id = ?
                """, rows, rows, rows, projected, geometryRows, bytesRead, snapshotId);

            tx.execute("DELETE FROM cadastre_import_stage WHERE run_id = ?", snapshotId);

            if (bootstrapProperties.isCleanupPreviousSnapshots()) {
                tx.execute("""
                    DELETE FROM external_dataset_feature
                     WHERE source_key = ? AND snapshot_id <> ?
                    """, SOURCE_KEY, snapshotId);
            }
            tx.execute("ANALYZE land_parcel");
            tx.execute("ANALYZE external_dataset_feature");
            return projected;
        });
    }

    private void resetFailedSnapshot(UUID snapshotId, String error) {
        try {
            dsl.transaction(configuration -> {
                DSLContext tx = configuration.dsl();
                tx.execute("DELETE FROM cadastre_import_stage WHERE run_id = ?", snapshotId);
                tx.execute("DELETE FROM external_dataset_feature WHERE snapshot_id = ?", snapshotId);
                tx.execute("""
                    UPDATE external_dataset_snapshot_run
                       SET status = 'FAILED', next_start_index = 0, pages_processed = 0,
                           records_staged = 0, expected_records = NULL, last_error = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND status <> 'COMPLETE'
                    """, truncate(error, 4_000), snapshotId);
            });
        } catch (RuntimeException ignored) {
            // Keep the original import exception. A later full sync can repair a failed snapshot row.
        }
    }

    private static void appendCsv(StringBuilder target, String value) {
        if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') target.append(',');
        if (value == null) return;
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') target.append('"');
            target.append(c);
        }
        target.append('"');
    }

    private static Map<String, Integer> headerMap(List<String> headers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String key = headers.get(i).trim().toLowerCase(Locale.ROOT);
            if (key.isBlank()) throw badRequest("CSV päises on tühi veerunimi.");
            if (result.putIfAbsent(key, i) != null) throw badRequest("CSV päises on korduv veerg: " + headers.get(i));
        }
        return result;
    }

    private static void requireColumn(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) throw badRequest("CSV päises puudub kohustuslik veerg: " + name);
    }

    private static String cell(Map<String, Integer> columns, List<String> row, String name) {
        Integer index = columns.get(name);
        if (index == null || index < 0 || index >= row.size()) return "";
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    private static void putText(ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) node.put(name, value);
    }

    private static void putSquareMetres(ObjectNode node, String name, String hectares, long rowNumber) {
        if (hectares == null || hectares.isBlank()) return;
        try {
            BigDecimal ha = new BigDecimal(hectares.replace(',', '.'));
            if (ha.signum() < 0) throw new NumberFormatException("negative");
            node.put(name, ha.multiply(BigDecimal.valueOf(10_000)).stripTrailingZeros());
        } catch (NumberFormatException exception) {
            throw badRequest("CSV real " + rowNumber + " sisaldab vigast pindala veerus " + name + ".");
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    static char detectDelimiter(String headerLine) {
        int semicolons = countDelimiter(headerLine, ';');
        int commas = countDelimiter(headerLine, ',');
        if (semicolons == 0 && commas == 0) throw badRequest("CSV veerueraldajat ei õnnestunud tuvastada.");
        return semicolons >= commas ? ';' : ',';
    }

    private static int countDelimiter(String value, char delimiter) {
        boolean quoted = false;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted && c == delimiter) {
                count++;
            }
        }
        return count;
    }

    static List<String> parseRecord(String text, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        if (quoted) throw badRequest("CSV-kirjes on lõpetamata jutumärkidega väli.");
        values.add(value.toString());
        return values;
    }

    private static String safeFilename(String value) {
        String filename = value == null || value.isBlank() ? "maaamet_kataster_forestiq.csv" : value;
        filename = filename.replace('\r', '_').replace('\n', '_').trim();
        return truncate(filename, 300);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ImportResult(UUID snapshotId, long rows, long geometryRows,
                               int projectedCadastres, long bytesRead, String filename) { }

    private record SnapshotRow(String cadastralCode, String sourceId, String propertiesJson,
                               String geometryJson, String rawFeatureJson) { }

    private static final class CsvRecordReader {
        private final BufferedReader reader;
        private final char delimiter;

        private CsvRecordReader(BufferedReader reader, char delimiter) {
            this.reader = reader;
            this.delimiter = delimiter;
        }

        private List<String> next() throws IOException {
            StringBuilder record = new StringBuilder();
            boolean quoted = false;
            boolean hasLine = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (hasLine) record.append('\n');
                record.append(line);
                quoted = quoteState(line, quoted);
                hasLine = true;
                if (!quoted) return parseRecord(record.toString(), delimiter);
            }
            if (!hasLine) return null;
            if (quoted) throw new IOException("CSV lõppes lõpetamata jutumärkidega välja sees.");
            return parseRecord(record.toString(), delimiter);
        }

        private static boolean quoteState(String line, boolean quoted) {
            boolean state = quoted;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) != '"') continue;
                if (state && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    state = !state;
                }
            }
            return state;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        private LimitedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long count) throws PayloadTooLargeIOException {
            bytesRead += count;
            if (bytesRead > maxBytes) throw new PayloadTooLargeIOException("Katastri CSV võib olla kuni 1 GB.");
        }

        private long bytesRead() { return bytesRead; }
    }

    private static final class PayloadTooLargeIOException extends IOException {
        private PayloadTooLargeIOException(String message) { super(message); }
    }
}
