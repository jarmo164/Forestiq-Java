package ee.forestiq.bootstrap;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import ee.forestiq.registry.RegistryProperties;

/**
 * Downloads complete public WFS datasets once, page by page, into shared snapshot tables.
 * Each page and its checkpoint are committed atomically so a stopped first-run import can resume.
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Temporary bootstrap importer kept until public dataset ingestion is cut over to Python."
)
@Service
public class PublicDatasetBootstrapService {
    public static final String MAAAMET_CADASTRE = "MAAAMET_CADASTRE";
    public static final String METSAREGISTER_TEATISED = "METSAREGISTER_TEATISED";
    public static final String METSAREGISTER_ERALDISED = "METSAREGISTER_ERALDISED";
    private static final Logger log = LoggerFactory.getLogger(PublicDatasetBootstrapService.class);
    private static final int PROVIDER_MAX_PAGE_SIZE = 1_000_000;

    private final DSLContext dsl;
    private final RegistryProperties registryProperties;
    private final BootstrapProperties bootstrapProperties;
    private final RestClient maaametClient;
    private final RestClient metsaregisterClient;

    public PublicDatasetBootstrapService(DSLContext dsl,
                                         RegistryProperties registryProperties,
                                         BootstrapProperties bootstrapProperties) {
        this.dsl = dsl;
        this.registryProperties = registryProperties;
        this.bootstrapProperties = bootstrapProperties;
        this.maaametClient = client(registryProperties.getMaaamet().getConnectTimeoutSeconds());
        this.metsaregisterClient = client(registryProperties.getMetsaregister().getConnectTimeoutSeconds());
    }

    public List<DatasetResult> synchronizeAll() {
        List<DatasetSpec> datasets = new ArrayList<>();
        datasets.add(new DatasetSpec(
            MAAAMET_CADASTRE,
            registryProperties.getMaaamet().getWfsUrl(),
            "kataster:ky_kehtiv", "tunnus", "tunnus", "tunnus",
            registryProperties.getMaaamet().getMaxAttempts(), maaametClient));

        // Complete public Metsaregister WFS catalogue published by Keskkonnaagentuur.
        // Operational layers keep deterministic source sorting. Auxiliary/raw layers
        // use GeoServer paging and are validated against numberMatched before publish.
        datasets.add(metsaregister(METSAREGISTER_ERALDISED,
            "metsaregister:eraldis", forestSortField(), "katastri_nr", "sys_id"));
        datasets.add(metsaregister("METSAREGISTER_ERALDIS_ELEMENT",
            "metsaregister:eraldis_element", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_ISEARASUSED",
            "metsaregister:isearasused", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KAHJUSTUSED",
            "metsaregister:kahjustused", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KATSEALAD",
            "metsaregister:katsealad", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KL_KASVUKOHT",
            "metsaregister:kl_kasvukoht", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KL_OMANDIVORM",
            "metsaregister:kl_omandivorm", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KL_RINNE",
            "metsaregister:kl_rinne", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KL_PUULIIK",
            "metsaregister:kl_puuliik", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_TEEMA_RAIE_DIAMEETER",
            "metsaregister:mr__teema_raie_diameeter", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_TEEMA_RAIE_LIIK",
            "metsaregister:mr__teema_raie_liik", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_TEEMA_RAIE_TAIUS",
            "metsaregister:mr__teema_raie_taius", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_TEEMA_RAIE_VANUS",
            "metsaregister:mr__teema_raie_vanus", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_NATURA_2000_ALAD",
            "metsaregister:natura_2000_alad", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_YP_KAITSEMETS",
            "metsaregister:yp_kaitsemets", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_MKE_ARHIIV",
            "metsaregister:mke_arhiiv", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_MUE",
            "metsaregister:mue", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_KUUSEKOOREYRASK_MKE",
            "metsaregister:kuusekooreyrask_mke", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_MKE",
            "metsaregister:mke", null, null, "id"));
        datasets.add(metsaregister("METSAREGISTER_TEATIS_ARHIIV",
            "metsaregister:teatis_arhiiv", forestSortField(), "katastri_nr", "sys_id"));
        datasets.add(metsaregister(METSAREGISTER_TEATISED,
            "metsaregister:teatis", forestSortField(), "katastri_nr", "sys_id"));

        List<DatasetResult> results = new ArrayList<>(datasets.size());
        for (DatasetSpec dataset : datasets) {
            results.add(synchronize(dataset));
        }
        return List.copyOf(results);
    }

    private DatasetSpec metsaregister(String key, String typeName, String sortField,
                                      String cadastralProperty, String sourceIdProperty) {
        return new DatasetSpec(key,
            registryProperties.getMetsaregister().getWfsUrl(),
            typeName, sortField, cadastralProperty, sourceIdProperty,
            registryProperties.getMetsaregister().getMaxAttempts(), metsaregisterClient);
    }

    private String forestSortField() {
        String value = registryProperties.getMetsaregister().getSortField();
        return value == null || value.isBlank() ? null : value;
    }

    private DatasetResult synchronize(DatasetSpec spec) {
        int pageSize = Math.max(1, Math.min(bootstrapProperties.getPageSize(), PROVIDER_MAX_PAGE_SIZE));
        int maxPages = Math.max(1, bootstrapProperties.getMaxPagesPerDataset());
        RunState run = resumeOrStart(spec);
        long startIndex = run.nextStartIndex();
        long pagesProcessed = run.pagesProcessed();
        long recordsStaged = run.recordsStaged();
        long expectedRecords = run.expectedRecords();

        log.info("Bootstrap dataset {} from startIndex={} (snapshot={})", spec.key(), startIndex, run.id());
        try {
            while (pagesProcessed < maxPages) {
                Page page = fetchPage(spec, startIndex, pageSize);
                long nextIndex = startIndex + page.features().size();
                long nextPages = pagesProcessed + 1;
                long nextRecords = recordsStaged + page.features().size();
                long nextExpected = page.numberMatched() >= 0 ? page.numberMatched() : expectedRecords;

                persistPage(spec, run.id(), page, nextIndex, nextPages, nextRecords, nextExpected);
                startIndex = nextIndex;
                pagesProcessed = nextPages;
                recordsStaged = nextRecords;
                expectedRecords = nextExpected;

                log.info("Bootstrap dataset {}: pages={}, rows={}, expected={}",
                    spec.key(), pagesProcessed, recordsStaged, expectedRecords < 0 ? "unknown" : expectedRecords);

                if (page.complete()) {
                    long publishedRows = publishSnapshot(spec.key(), run.id(), recordsStaged, expectedRecords);
                    return new DatasetResult(spec.key(), run.id(), pagesProcessed, publishedRows, expectedRecords);
                }
                if (page.features().isEmpty()) {
                    throw new IllegalStateException("WFS returned an empty non-final page for " + spec.key());
                }
                sleepBetweenRequests();
            }
            throw new IllegalStateException("Dataset " + spec.key() + " exceeded forestiq.bootstrap.max-pages-per-dataset=" + maxPages);
        } catch (RuntimeException exception) {
            markFailed(run.id(), exception);
            throw exception;
        }
    }

    private RunState resumeOrStart(DatasetSpec spec) {
        Record existing = dsl.fetchOne("""
            SELECT id, next_start_index, pages_processed, records_staged, expected_records
              FROM external_dataset_snapshot_run
             WHERE source_key = ? AND status IN ('RUNNING', 'FAILED')
             ORDER BY started_at DESC
             LIMIT 1
            """, spec.key());
        if (existing != null) {
            UUID id = existing.get("id", UUID.class);
            dsl.execute("""
                UPDATE external_dataset_snapshot_run
                   SET status = 'RUNNING', last_error = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, id);
            return new RunState(id,
                value(existing.get("next_start_index", Long.class)),
                value(existing.get("pages_processed", Long.class)),
                value(existing.get("records_staged", Long.class)),
                nullableValue(existing.get("expected_records", Long.class)));
        }

        UUID id = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO external_dataset_snapshot_run
                (id, source_key, source_url, type_name, status, expected_records)
            VALUES (?, ?, ?, ?, 'RUNNING', NULL)
            """, id, spec.key(), spec.url(), spec.typeName());
        return new RunState(id, 0, 0, 0, -1);
    }

    private Page fetchPage(DatasetSpec spec, long startIndex, int pageSize) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(spec.url())
            .queryParam("service", "WFS")
            .queryParam("version", "2.0.0")
            .queryParam("request", "GetFeature")
            .queryParam("typeNames", spec.typeName())
            .queryParam("outputFormat", "application/json")
            .queryParam("srsName", "EPSG:4326")
            .queryParam("count", pageSize)
            .queryParam("startIndex", startIndex);
        if (spec.sortField() != null && !spec.sortField().isBlank()) {
            uriBuilder.queryParam("sortBy", spec.sortField() + " A");
        }
        URI uri = uriBuilder.encode().build().toUri();

        JsonNode response = withRetry(spec.key(), spec.maxAttempts(), () ->
            spec.client().get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class));
        if (response == null || !response.path("features").isArray()) {
            throw new IllegalStateException("WFS response has no features array for " + spec.key());
        }
        List<JsonNode> features = new ArrayList<>();
        response.path("features").forEach(features::add);
        long matched = longValue(response.path("numberMatched"));
        long returned = longValue(response.path("numberReturned"));
        if (returned < 0) returned = features.size();

        // Do not use a short page as an end marker: GeoServer may enforce a page
        // size lower than the requested count. If numberMatched is unavailable,
        // request one final empty page instead of silently accepting a partial set.
        boolean complete = returned == 0
            || (matched >= 0 && startIndex + features.size() >= matched);
        return new Page(List.copyOf(features), matched, complete);
    }

    private void persistPage(DatasetSpec spec, UUID snapshotId, Page page,
                             long nextIndex, long pages, long records, long expected) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            BatchBindStep batch = tx.batch("""
                INSERT INTO external_dataset_feature
                    (source_key, snapshot_id, feature_key, source_id, cadastral_code,
                     properties, geometry, raw_feature)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                ON CONFLICT (source_key, snapshot_id, feature_key) DO UPDATE SET
                    source_id = EXCLUDED.source_id,
                    cadastral_code = EXCLUDED.cadastral_code,
                    properties = EXCLUDED.properties,
                    geometry = EXCLUDED.geometry,
                    raw_feature = EXCLUDED.raw_feature,
                    fetched_at = CURRENT_TIMESTAMP
                """);
            int batchSize = 0;
            for (JsonNode feature : page.features()) {
                JsonNode properties = feature.path("properties");
                if (!properties.isObject()) continue;
                JsonNode geometry = feature.path("geometry");
                String sourceId = text(properties, spec.sourceIdProperty());
                if (sourceId == null) sourceId = text(feature, "id");
                String cadastralCode = text(properties, spec.cadastralProperty());
                String featureKey = sourceId == null || sourceId.isBlank()
                    ? sha256(feature.toString()) : sourceId;
                if (featureKey.length() > 300) featureKey = sha256(featureKey);
                if (sourceId != null && sourceId.length() > 300) sourceId = sourceId.substring(0, 300);
                batch.bind(spec.key(), snapshotId, featureKey, sourceId, cadastralCode,
                    properties.toString(), geometry.isObject() ? geometry.toString() : null, feature.toString());
                batchSize++;
            }
            if (batchSize > 0) {
                batch.execute();
            }
            tx.execute("""
                UPDATE external_dataset_snapshot_run
                   SET next_start_index = ?, pages_processed = ?, records_staged = ?,
                       expected_records = ?, updated_at = CURRENT_TIMESTAMP, last_error = NULL
                 WHERE id = ?
                """, nextIndex, pages, records, expected >= 0 ? expected : null, snapshotId);
        });
    }

    private long publishSnapshot(String sourceKey, UUID snapshotId, long records, long expected) {
        Object distinctValue = dsl.fetchValue("""
            SELECT count(*)
              FROM external_dataset_feature
             WHERE source_key = ? AND snapshot_id = ?
            """, sourceKey, snapshotId);
        long actualRows = distinctValue instanceof Number number ? number.longValue() : 0L;
        if (expected >= 0 && actualRows != expected) {
            resetSnapshotForCleanRetry(snapshotId);
            throw new IllegalStateException("Dataset " + sourceKey + " expected " + expected
                + " distinct rows but staged " + actualRows + "; checkpoint was reset for a clean retry.");
        }

        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute("""
                INSERT INTO external_dataset_current (source_key, snapshot_id, published_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (source_key) DO UPDATE SET
                    snapshot_id = EXCLUDED.snapshot_id,
                    published_at = EXCLUDED.published_at
                """, sourceKey, snapshotId);
            tx.execute("""
                UPDATE external_dataset_snapshot_run
                   SET status = 'COMPLETE', records_staged = ?, expected_records = ?,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, last_error = NULL
                 WHERE id = ?
                """, actualRows, expected >= 0 ? expected : null, snapshotId);
            if (bootstrapProperties.isCleanupPreviousSnapshots()) {
                tx.execute("""
                    DELETE FROM external_dataset_feature
                     WHERE source_key = ? AND snapshot_id <> ?
                    """, sourceKey, snapshotId);
            }
        });
        return actualRows;
    }

    private void resetSnapshotForCleanRetry(UUID snapshotId) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute("DELETE FROM external_dataset_feature WHERE snapshot_id = ?", snapshotId);
            tx.execute("""
                UPDATE external_dataset_snapshot_run
                   SET status = 'FAILED', next_start_index = 0, pages_processed = 0,
                       records_staged = 0, expected_records = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, snapshotId);
        });
    }

    private void markFailed(UUID runId, RuntimeException exception) {
        String message = safeMessage(exception);
        try {
            dsl.execute("""
                UPDATE external_dataset_snapshot_run
                   SET status = 'FAILED', last_error = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, message, runId);
        } catch (RuntimeException ignored) {
            log.warn("Could not persist bootstrap failure for snapshot {}", runId);
        }
    }

    /** Projects the complete shared snapshots into the existing ForestIQ tenant tables. */
    public ProjectionResult projectToTenantTables() {
        int cadastres = dsl.execute("""
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
              FROM external_dataset_current c
              JOIN external_dataset_feature f
                ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
             WHERE c.source_key = 'MAAAMET_CADASTRE'
               AND p.cadastral_code = f.cadastral_code
            """);

        dsl.execute("""
            DELETE FROM cadastre_notification n
             USING land_parcel p
             WHERE n.organization_id = p.organization_id
               AND n.cadastral_code = p.cadastral_code
               AND n.archived = FALSE
            """);
        int notifications = dsl.execute("""
            INSERT INTO cadastre_notification
                (organization_id, cadastral_code, notification_number, amount_to_be_cut,
                 area, decision, work_code, archived)
            SELECT p.organization_id, p.cadastral_code,
                   CASE WHEN COALESCE(f.properties->>'sys_id', '') ~ '^[0-9]+$'
                        THEN (f.properties->>'sys_id')::bigint END,
                   CASE WHEN COALESCE(f.properties->>'raiutav_maht', '') ~ '^[0-9]+([.][0-9]+)?$'
                        THEN (f.properties->>'raiutav_maht')::numeric END,
                   CASE WHEN COALESCE(f.properties->>'pindala', '') ~ '^[0-9]+([.][0-9]+)?$'
                        THEN (f.properties->>'pindala')::numeric END,
                   NULLIF(f.properties->>'otsus', ''), NULLIF(f.properties->>'too_kood', ''), FALSE
              FROM land_parcel p
              JOIN external_dataset_current c ON c.source_key = 'METSAREGISTER_TEATISED'
              JOIN external_dataset_feature f
                ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
               AND f.cadastral_code = p.cadastral_code
            """);

        dsl.execute("""
            DELETE FROM forest_registry_feature f
             USING land_parcel p
             WHERE f.organization_id = p.organization_id
               AND f.cadastral_code = p.cadastral_code
               AND f.source_layer = 'METSAREGISTER_ERALDISED'
            """);
        int compartments = dsl.execute("""
            INSERT INTO forest_registry_feature
                (organization_id, cadastral_code, source_layer, source_id, title,
                 work_code, decision, area, volume, attributes, geometry)
            SELECT p.organization_id, p.cadastral_code, 'METSAREGISTER_ERALDISED', f.source_id,
                   COALESCE(NULLIF(f.properties->>'eraldise_nr', ''), NULLIF(f.properties->>'kinnistu_nimetus', ''), f.source_id),
                   NULLIF(f.properties->>'too_kood', ''), NULLIF(f.properties->>'otsus', ''),
                   CASE WHEN COALESCE(f.properties->>'pindala', '') ~ '^[0-9]+([.][0-9]+)?$'
                        THEN (f.properties->>'pindala')::numeric END,
                   CASE WHEN COALESCE(f.properties->>'raiutav_maht', '') ~ '^[0-9]+([.][0-9]+)?$'
                        THEN (f.properties->>'raiutav_maht')::numeric
                        WHEN COALESCE(f.properties->>'tagavara_y_ha', '') ~ '^[0-9]+([.][0-9]+)?$'
                        THEN (f.properties->>'tagavara_y_ha')::numeric END,
                   f.properties::text, f.geometry::text
              FROM land_parcel p
              JOIN external_dataset_current c ON c.source_key = 'METSAREGISTER_ERALDISED'
              JOIN external_dataset_feature f
                ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
               AND f.cadastral_code = p.cadastral_code
            """);
        dsl.execute("""
            UPDATE land_parcel p
               SET mk_date = (extract(epoch FROM CURRENT_TIMESTAMP) * 1000)::bigint,
                   updated_at = CURRENT_TIMESTAMP
             WHERE EXISTS (
                 SELECT 1
                   FROM external_dataset_current c
                   JOIN external_dataset_feature f
                     ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
                  WHERE c.source_key = 'METSAREGISTER_ERALDISED'
                    AND f.cadastral_code = p.cadastral_code)
            """);

        appendBootstrapAuditLogs();
        return new ProjectionResult(cadastres, notifications, compartments);
    }

    private void appendBootstrapAuditLogs() {
        dsl.execute("""
            INSERT INTO registry_sync_log
                (organization_id, cadastral_code, registry_name, request_url, status,
                 records_updated, synced_by_subject)
            SELECT p.organization_id, p.cadastral_code, 'MAAAMET_WFS', 'bootstrap:MAAAMET_CADASTRE',
                   'SUCCESS', 1, 'system-bootstrap'
              FROM land_parcel p
             WHERE EXISTS (
                 SELECT 1 FROM external_dataset_current c
                 JOIN external_dataset_feature f
                   ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
                WHERE c.source_key = 'MAAAMET_CADASTRE' AND f.cadastral_code = p.cadastral_code)
            """);
        appendForestAudit(METSAREGISTER_TEATISED, "METSAREGISTER_TEATISED");
        appendForestAudit(METSAREGISTER_ERALDISED, "METSAREGISTER_ERALDISED");
    }

    private void appendForestAudit(String sourceKey, String registryName) {
        dsl.execute("""
            INSERT INTO registry_sync_log
                (organization_id, cadastral_code, registry_name, request_url, status,
                 records_updated, synced_by_subject)
            SELECT p.organization_id, p.cadastral_code, ?, ?, 'SUCCESS',
                   count(f.feature_key)::int, 'system-bootstrap'
              FROM land_parcel p
              JOIN external_dataset_current c ON c.source_key = ?
              LEFT JOIN external_dataset_feature f
                ON f.source_key = c.source_key AND f.snapshot_id = c.snapshot_id
               AND f.cadastral_code = p.cadastral_code
             GROUP BY p.organization_id, p.cadastral_code
            """, registryName, "bootstrap:" + sourceKey, sourceKey);
    }

    private void sleepBetweenRequests() {
        long delay = Math.max(0L, bootstrapProperties.getMinRequestIntervalMs());
        if (delay == 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bootstrap interrupted", exception);
        }
    }

    private static RestClient client(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static <T> T withRetry(String source, int configuredAttempts, Supplier<T> request) {
        int attempts = Math.max(1, Math.min(configuredAttempts, 8));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(Math.min(5_000L, 500L * attempt));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Bootstrap interrupted while retrying " + source, interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException(source + " did not respond after " + attempts + " attempts", last);
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null || key.isBlank()) return null;
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static long longValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return -1;
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        message = message.replaceAll("(?i)(token|password|authorization|secret)\\s*[:=]\\s*[^,; ]+", "$1=[redacted]");
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.substring(0, Math.min(1_000, message.length()));
    }

    private static long value(Long value) { return value == null ? 0L : value; }
    private static long nullableValue(Long value) { return value == null ? -1L : value; }

    @ExternalSystemBoundary(
        value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
        reason = "Carries the legacy bootstrap HTTP client until public dataset ingestion is cut over to Python."
    )
    private record DatasetSpec(String key, String url, String typeName, String sortField,
                               String cadastralProperty, String sourceIdProperty,
                               int maxAttempts, RestClient client) { }
    private record RunState(UUID id, long nextStartIndex, long pagesProcessed,
                            long recordsStaged, long expectedRecords) { }
    private record Page(List<JsonNode> features, long numberMatched, boolean complete) { }

    public record DatasetResult(String sourceKey, UUID snapshotId, long pages, long rows, long expectedRows) { }
    public record ProjectionResult(int cadastresUpdated, int notificationsInserted, int compartmentsInserted) { }
}
