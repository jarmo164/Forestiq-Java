package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resumable bulk recovery for Metsaregister layers. Provider pages are fetched
 * outside a DB transaction and normalized into transient staging. Live parcel
 * projections are replaced only after the whole bounded batch has been read.
 */
@Service
class RegistryBulkRecoveryService {
    private static final String NOTIFICATIONS = "METSAREGISTER_TEATISED";
    private static final String PLAN = "METSAREGISTER_ERALDISED";
    private static final String SYSTEM_SUBJECT = "system-registry-bulk-recovery";

    private final DSLContext dsl;
    private final MetsaregisterWfsClient client;
    private final RegistryProperties properties;
    private final RegistryPayloadPolicy payloadPolicy;
    private final RegistrySyncAuditRepository auditRepository;
    private final RegistryDataRepository dataRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final Counter requests;
    private final Counter pages;
    private final Counter rows;
    private final Counter failures;

    RegistryBulkRecoveryService(DSLContext dsl, MetsaregisterWfsClient client, RegistryProperties properties,
                                RegistryPayloadPolicy payloadPolicy, RegistrySyncAuditRepository auditRepository,
                                RegistryDataRepository dataRepository, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager, MeterRegistry meterRegistry) {
        this.dsl = dsl;
        this.client = client;
        this.properties = properties;
        this.payloadPolicy = payloadPolicy;
        this.auditRepository = auditRepository;
        this.dataRepository = dataRepository;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setName("registry-bulk-wfs-checkpoint");
        this.requests = meterRegistry.counter("forestiq.registry.bulk.requests");
        this.pages = meterRegistry.counter("forestiq.registry.bulk.pages");
        this.rows = meterRegistry.counter("forestiq.registry.bulk.rows");
        this.failures = meterRegistry.counter("forestiq.registry.bulk.failures");
    }

    BulkRun recover(UUID organizationId, String registry, List<RegistryDataRepository.ParcelRef> staleParcels) {
        if (!NOTIFICATIONS.equals(registry) && !PLAN.equals(registry)) {
            throw new IllegalArgumentException("Bulk WFS recovery supports only Metsaregister projections");
        }
        Checkpoint checkpoint = loadCheckpoint(organizationId, registry);
        if (checkpoint == null) {
            int batchSize = Math.max(1, Math.min(properties.getMetsaregister().getBulkBatchSize(), 100));
            List<String> codes = staleParcels.stream().map(RegistryDataRepository.ParcelRef::cadastralCode)
                .distinct().limit(batchSize).toList();
            if (codes.isEmpty()) return new BulkRun(0, 0, 0, true, false);
            checkpoint = createCheckpoint(organizationId, registry, codes);
        }

        String typeName = NOTIFICATIONS.equals(registry) ? "metsaregister:teatis" : "metsaregister:eraldis";
        String filter = cqlBatchFilter(checkpoint.cadastralCodes());
        int maxPagesThisRun = Math.max(1, Math.min(properties.getMetsaregister().getBulkPagesPerRun(), 100));
        int pagesThisRun = 0;
        long rowsThisRun = 0;
        try {
            for (int i = 0; i < maxPagesThisRun; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ExternalRegistryService.RegistryException("Metsaregister bulk recovery interrupted");
                }
                requests.increment();
                MetsaregisterWfsClient.PageResult page = client.fetchPage(typeName, filter, checkpoint.nextStartIndex());
                pages.increment();
                rows.increment(page.records());
                pagesThisRun++;
                rowsThisRun += page.records();
                Checkpoint current = checkpoint;
                checkpoint = transaction.execute(status -> stageAndAdvance(current, page));
                if (page.complete()) {
                    Checkpoint completed = checkpoint;
                    transaction.executeWithoutResult(status -> applyCompletedBatch(completed));
                    return new BulkRun(completed.cadastralCodes().size(), pagesThisRun, rowsThisRun, true, true);
                }
            }
            return new BulkRun(checkpoint.cadastralCodes().size(), pagesThisRun, rowsThisRun, false, true);
        } catch (RuntimeException exception) {
            failures.increment();
            Checkpoint failedCheckpoint = checkpoint;
            try {
                transaction.executeWithoutResult(status -> markFailure(failedCheckpoint, exception));
            } catch (RuntimeException ignored) {
                // Never replace the provider failure with an observability write failure.
            }
            throw exception;
        }
    }

    private Checkpoint stageAndAdvance(Checkpoint checkpoint, MetsaregisterWfsClient.PageResult page) {
        for (JsonNode feature : page.features()) {
            JsonNode p = feature.path("properties");
            if (!p.isObject()) continue;
            String cadastralCode = text(p, "katastri_nr");
            if (cadastralCode == null || !checkpoint.cadastralCodes().contains(cadastralCode)) {
                throw new ExternalRegistryService.RegistryException(
                    "Metsaregister WFS returned a feature outside the requested cadastral batch");
            }
            String sourceId = textOrNumber(p, "sys_id", "id");
            String title = firstText(p, "teatise_nr", "eraldise_nr", "kinnistu_nimetus", "kinnistu_nr");
            String sourceHash = payloadPolicy.sourceHash(p);
            String featureKey = sourceId != null ? sourceId : sourceHash;
            dsl.insertInto(table("registry_bulk_sync_feature"))
                .columns(field("batch_id"), field("organization_id"), field("registry_name"), field("cadastral_code"),
                    field("feature_key"), field("source_id"), field("title"), field("work_code"), field("decision"),
                    field("area"), field("volume"), field("source_hash"))
                .values(checkpoint.batchId(), checkpoint.organizationId(), checkpoint.registry(), cadastralCode,
                    featureKey, sourceId, title, text(p, "too_kood"), text(p, "otsus"), decimal(p, "pindala"),
                    decimal(p, "raiutav_maht", "tagavara_y_ha"), sourceHash)
                .onConflict(field("batch_id"), field("cadastral_code"), field("feature_key")).doUpdate()
                .set(field("source_id"), sourceId).set(field("title"), title)
                .set(field("work_code"), text(p, "too_kood")).set(field("decision"), text(p, "otsus"))
                .set(field("area"), decimal(p, "pindala"))
                .set(field("volume"), decimal(p, "raiutav_maht", "tagavara_y_ha"))
                .set(field("source_hash"), sourceHash)
                .execute();
        }
        int nextPages = checkpoint.pagesProcessed() + 1;
        long staged = dsl.selectCount().from(table("registry_bulk_sync_feature"))
            .where(field(name("batch_id"), UUID.class).eq(checkpoint.batchId())).fetchOne(0, long.class);
        dsl.update(table("registry_bulk_sync_checkpoint"))
            .set(field("next_start_index"), page.nextStartIndex())
            .set(field("pages_processed"), nextPages)
            .set(field("records_staged"), staged)
            .set(field("last_failure_at"), (Instant) null)
            .set(field("last_error"), (String) null)
            .set(field("updated_at"), Instant.now())
            .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                .and(field(name("registry_name"), String.class).eq(checkpoint.registry())))
            .execute();
        return new Checkpoint(checkpoint.organizationId(), checkpoint.registry(), checkpoint.batchId(),
            checkpoint.cadastralCodes(), page.nextStartIndex(), nextPages, staged);
    }

    private void applyCompletedBatch(Checkpoint checkpoint) {
        for (String cadastralCode : checkpoint.cadastralCodes()) {
            RegistryDataRepository.ParcelOwnerRef parcel = dataRepository.parcelOwnerRef(
                checkpoint.organizationId(), cadastralCode);
            List<StagedFeature> staged = stagedForParcel(checkpoint.batchId(), cadastralCode);
            int count;
            if (NOTIFICATIONS.equals(checkpoint.registry())) {
                Set<String> previous = dataRepository.activeNotificationKeys(checkpoint.organizationId(), cadastralCode);
                dsl.deleteFrom(table("cadastre_notification"))
                    .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                        .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                        .and(field(name("archived"), Boolean.class).eq(false))).execute();
                count = dsl.execute("""
                    INSERT INTO cadastre_notification
                        (organization_id, cadastral_code, notification_number, work_code, decision, area)
                    SELECT organization_id, cadastral_code,
                           CASE WHEN source_id ~ '^[0-9]+$' THEN source_id::bigint ELSE NULL END,
                           work_code, decision, area
                      FROM registry_bulk_sync_feature
                     WHERE batch_id=? AND cadastral_code=?
                    """, checkpoint.batchId(), cadastralCode);
                for (StagedFeature feature : staged) {
                    String key = feature.sourceId() != null ? feature.sourceId() : feature.featureKey();
                    if (!previous.contains(key)) {
                        for (UUID ownerId : parcel.ownerIds()) {
                            auditRepository.recordDataSignal(checkpoint.organizationId(), ownerId, parcel.parcelId(),
                                "NEW_FOREST_NOTIFICATION", NOTIFICATIONS, cadastralCode + ":" + key,
                                "Metsaregistris lisandus uus metsateatis " + display(feature) + ".",
                                Map.of("sourceHash", feature.sourceHash(), "batchId", checkpoint.batchId().toString()),
                                SYSTEM_SUBJECT);
                        }
                    }
                }
            } else {
                Map<String, String> previous = dataRepository.forestPlanFeatureVersions(
                    checkpoint.organizationId(), cadastralCode);
                dsl.deleteFrom(table("forest_registry_feature"))
                    .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                        .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                        .and(field(name("source_layer"), String.class).eq(PLAN))).execute();
                count = dsl.execute("""
                    INSERT INTO forest_registry_feature
                        (organization_id, cadastral_code, source_layer, source_id, title, work_code, decision,
                         area, volume, attributes)
                    SELECT organization_id, cadastral_code, ?, source_id, title, work_code, decision,
                           area, volume, jsonb_build_object('sourceHash', source_hash)::text
                      FROM registry_bulk_sync_feature
                     WHERE batch_id=? AND cadastral_code=?
                    """, PLAN, checkpoint.batchId(), cadastralCode);
                if (count > 0) {
                    dsl.update(table("land_parcel")).set(field("mk_date"), System.currentTimeMillis())
                        .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                            .and(field(name("cadastral_code"), String.class).eq(cadastralCode))).execute();
                }
                for (StagedFeature feature : staged) {
                    String key = feature.sourceId() != null ? feature.sourceId() : feature.featureKey();
                    if (!feature.sourceHash().equals(previous.get(key))) {
                        for (UUID ownerId : parcel.ownerIds()) {
                            auditRepository.recordDataSignal(checkpoint.organizationId(), ownerId, parcel.parcelId(),
                                "NEW_FOREST_COMPARTMENT", PLAN, cadastralCode + ":" + key + ":" + feature.sourceHash(),
                                "Metsaregistri eraldise andmed muutusid: " + display(feature) + ".",
                                Map.of("sourceHash", feature.sourceHash(), "batchId", checkpoint.batchId().toString()),
                                SYSTEM_SUBJECT);
                        }
                    }
                }
            }
            auditRepository.logSync(checkpoint.organizationId(), cadastralCode, checkpoint.registry(), "SUCCESS", count,
                null, Map.of("batchId", checkpoint.batchId().toString(), "pages", checkpoint.pagesProcessed(),
                    "recordsStaged", checkpoint.recordsStaged()), null, SYSTEM_SUBJECT);
        }
        dsl.deleteFrom(table("registry_bulk_sync_feature"))
            .where(field(name("batch_id"), UUID.class).eq(checkpoint.batchId())).execute();
        dsl.deleteFrom(table("registry_bulk_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                .and(field(name("registry_name"), String.class).eq(checkpoint.registry()))).execute();
    }

    private List<StagedFeature> stagedForParcel(UUID batchId, String cadastralCode) {
        return dsl.select(field(name("feature_key"), String.class), field(name("source_id"), String.class),
                field(name("title"), String.class), field(name("source_hash"), String.class))
            .from(table("registry_bulk_sync_feature"))
            .where(field(name("batch_id"), UUID.class).eq(batchId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode)))
            .fetch(record -> new StagedFeature(record.value1(), record.value2(), record.value3(), record.value4()));
    }

    private Checkpoint createCheckpoint(UUID organizationId, String registry, List<String> codes) {
        UUID batchId = UUID.randomUUID();
        String codesJson;
        try { codesJson = objectMapper.writeValueAsString(codes); }
        catch (Exception exception) { throw new IllegalStateException("Cannot encode bulk registry batch", exception); }
        transaction.executeWithoutResult(status -> dsl.insertInto(table("registry_bulk_sync_checkpoint"))
            .columns(field("organization_id"), field("registry_name"), field("batch_id"), field("cadastral_codes"),
                field("next_start_index"), field("pages_processed"), field("records_staged"))
            .values(organizationId, registry, batchId, field("?::jsonb", Object.class, codesJson), 0L, 0, 0L)
            .onConflict(field("organization_id"), field("registry_name")).doNothing().execute());
        Checkpoint existing = loadCheckpoint(organizationId, registry);
        if (existing == null) throw new IllegalStateException("Bulk registry checkpoint was not persisted");
        return existing;
    }

    private Checkpoint loadCheckpoint(UUID organizationId, String registry) {
        var record = dsl.select(field(name("batch_id"), UUID.class), field(name("cadastral_codes"), String.class),
                field(name("next_start_index"), Long.class), field(name("pages_processed"), Integer.class),
                field(name("records_staged"), Long.class))
            .from(table("registry_bulk_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(organizationId)
                .and(field(name("registry_name"), String.class).eq(registry))).fetchOne();
        if (record == null) return null;
        try {
            JsonNode node = objectMapper.readTree(record.value2());
            List<String> codes = new ArrayList<>();
            node.forEach(value -> codes.add(value.asText()));
            return new Checkpoint(organizationId, registry, record.value1(), List.copyOf(codes),
                record.value3() == null ? 0 : record.value3(), record.value4() == null ? 0 : record.value4(),
                record.value5() == null ? 0 : record.value5());
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid bulk registry checkpoint", exception);
        }
    }

    private void markFailure(Checkpoint checkpoint, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        message = message.replaceAll("[\\r\\n]+", " ");
        if (message.length() > 500) message = message.substring(0, 500);
        dsl.update(table("registry_bulk_sync_checkpoint"))
            .set(field("last_failure_at"), Instant.now()).set(field("last_error"), message)
            .set(field("updated_at"), Instant.now())
            .where(field(name("organization_id"), UUID.class).eq(checkpoint.organizationId())
                .and(field(name("registry_name"), String.class).eq(checkpoint.registry()))).execute();
    }

    private static String cqlBatchFilter(List<String> codes) {
        if (codes.isEmpty()) throw new IllegalArgumentException("Bulk WFS batch cannot be empty");
        return "katastri_nr IN (" + codes.stream()
            .map(code -> "'" + code.replace("'", "''") + "'")
            .reduce((left, right) -> left + "," + right).orElseThrow() + ")";
    }

    private static String display(StagedFeature feature) {
        return feature.title() == null || feature.title().isBlank() ? feature.featureKey() : feature.title();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String textOrNumber(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isIntegralNumber() || value.isTextual()) return value.asText();
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber()) return value.decimalValue();
            if (value.isTextual()) {
                try { return new BigDecimal(value.asText()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    record BulkRun(int cadastres, int pages, long records, boolean completed, boolean attempted) { }
    private record Checkpoint(UUID organizationId, String registry, UUID batchId, List<String> cadastralCodes,
                              long nextStartIndex, int pagesProcessed, long recordsStaged) { }
    private record StagedFeature(String featureKey, String sourceId, String title, String sourceHash) { }
}
