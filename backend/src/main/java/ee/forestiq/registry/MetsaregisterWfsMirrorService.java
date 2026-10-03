package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resumable provider-level ingestion for complete Metsaregister WFS layers.
 *
 * Pages are staged into an immutable generation. The current layer pointer is
 * switched only after all provider rows have been consumed and count-checked,
 * preserving the previous generation through failures and process restarts.
 */
@Service
class MetsaregisterWfsMirrorService {
    private final DSLContext dsl;
    private final MetsaregisterWfsClient client;
    private final RegistryProperties properties;
    private final RegistryPayloadPolicy payloadPolicy;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    MetsaregisterWfsMirrorService(DSLContext dsl, MetsaregisterWfsClient client, RegistryProperties properties,
                                 RegistryPayloadPolicy payloadPolicy, ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.client = client;
        this.properties = properties;
        this.payloadPolicy = payloadPolicy;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setName("metsaregister-wfs-mirror-page");
    }

    LayerRun syncLayer(String layerName) {
        LayerConfig config = loadLayerConfig(layerName);
        Generation generation = loadOrCreateGeneration(config.layerName());
        int maxPagesThisRun = Math.max(1, Math.min(properties.getMetsaregister().getBulkPagesPerRun(), 100));
        int pagesThisRun = 0;
        long rowsThisRun = 0;
        int retriesThisRun = 0;

        markSyncing(config.layerName());
        try {
            for (int i = 0; i < maxPagesThisRun; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ExternalRegistryService.RegistryException("Metsaregister full-layer sync interrupted");
                }
                MetsaregisterWfsClient.PageResult page = client.fetchFullPage(
                    config.layerName(), config.sortProperty(), generation.nextStartIndex());
                pagesThisRun++;
                rowsThisRun += page.records();
                retriesThisRun += Math.max(0, page.attempts() - 1);
                Generation current = generation;
                generation = transaction.execute(status -> stageAndAdvance(config, current, page));
                if (generation == null) throw new IllegalStateException("WFS mirror checkpoint update returned no state");

                if (page.complete()) {
                    Generation completed = generation;
                    transaction.executeWithoutResult(status -> publishGeneration(completed));
                    return new LayerRun(config.layerName(), completed.id(), pagesThisRun, rowsThisRun,
                        completed.rowsStaged(), true, retriesThisRun);
                }
            }
            return new LayerRun(config.layerName(), generation.id(), pagesThisRun, rowsThisRun,
                generation.rowsStaged(), false, retriesThisRun);
        } catch (RuntimeException exception) {
            markFailure(config.layerName(), generation.id(), exception);
            throw exception;
        }
    }

    List<LayerRun> syncReadyLayers(int maxLayers) {
        int limit = Math.max(1, Math.min(maxLayers, 50));
        List<String> layers = dsl.select(field(name("layer_name"), String.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("approved"), Boolean.class).isTrue()
                .and(field(name("enabled"), Boolean.class).isTrue())
                .and(field(name("provider_present"), Boolean.class).isTrue())
                .and(field(name("sort_property"), String.class).isNotNull())
                .and(field(name("state"), String.class).notIn("PENDING_SCHEMA", "UNSUPPORTED", "SCHEMA_DRIFT")))
            .orderBy(field(name("last_success_at")).asc().nullsFirst(), field(name("layer_name")).asc())
            .limit(limit)
            .fetch(field(name("layer_name"), String.class));
        return layers.stream().map(this::syncLayer).toList();
    }

    private LayerConfig loadLayerConfig(String layerName) {
        var record = dsl.select(
                field(name("layer_name"), String.class),
                field(name("approved"), Boolean.class),
                field(name("enabled"), Boolean.class),
                field(name("provider_present"), Boolean.class),
                field(name("state"), String.class),
                field(name("identity_property"), String.class),
                field(name("sort_property"), String.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(layerName))
            .fetchOne();
        if (record == null) throw new IllegalArgumentException("Unknown Metsaregister WFS layer: " + layerName);
        if (!Boolean.TRUE.equals(record.value2()) || !Boolean.TRUE.equals(record.value3())) {
            throw new IllegalStateException("Metsaregister WFS layer is not approved/enabled: " + layerName);
        }
        if (!Boolean.TRUE.equals(record.value4())) {
            throw new IllegalStateException("Metsaregister WFS layer is missing from provider capabilities: " + layerName);
        }
        String state = record.value5();
        if ("PENDING_SCHEMA".equals(state) || "UNSUPPORTED".equals(state) || "SCHEMA_DRIFT".equals(state)) {
            throw new IllegalStateException("Metsaregister WFS layer schema is not ingestible: " + layerName
                + " (" + state + ")");
        }
        String identity = record.value6();
        String sort = record.value7();
        if (identity == null || sort == null) {
            throw new IllegalStateException("Metsaregister WFS layer has no deterministic identity/order: " + layerName);
        }
        return new LayerConfig(layerName, identity, sort);
    }

    private Generation loadOrCreateGeneration(String layerName) {
        Generation existing = loadStagingGeneration(layerName);
        if (existing != null) return existing;
        UUID id = UUID.randomUUID();
        try {
            dsl.insertInto(table("metsaregister_wfs_generation"))
                .columns(field(name("id")), field(name("layer_name")), field(name("state")))
                .values(id, layerName, "STAGING")
                .execute();
            return new Generation(id, layerName, 0L, 0, 0L, null);
        } catch (RuntimeException race) {
            existing = loadStagingGeneration(layerName);
            if (existing != null) return existing;
            throw race;
        }
    }

    private Generation loadStagingGeneration(String layerName) {
        var record = dsl.select(
                field(name("id"), UUID.class),
                field(name("next_start_index"), Long.class),
                field(name("pages_processed"), Integer.class),
                field(name("rows_staged"), Long.class),
                field(name("provider_number_matched"), Long.class))
            .from(table("metsaregister_wfs_generation"))
            .where(field(name("layer_name"), String.class).eq(layerName)
                .and(field(name("state"), String.class).eq("STAGING")))
            .fetchOne();
        if (record == null) return null;
        return new Generation(record.value1(), layerName,
            record.value2() == null ? 0L : record.value2(),
            record.value3() == null ? 0 : record.value3(),
            record.value4() == null ? 0L : record.value4(),
            record.value5());
    }

    private Generation stageAndAdvance(LayerConfig config, Generation generation,
                                       MetsaregisterWfsClient.PageResult page) {
        if (generation.providerNumberMatched() != null && page.numberMatched() >= 0
            && generation.providerNumberMatched() != page.numberMatched()) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS numberMatched changed during layer generation");
        }
        Long providerMatched = generation.providerNumberMatched() != null
            ? generation.providerNumberMatched()
            : page.numberMatched() >= 0 ? page.numberMatched() : null;

        for (JsonNode feature : page.features()) {
            String sourceId = identityValue(feature, config.identityProperty());
            if (sourceId == null) {
                throw new ExternalRegistryService.RegistryException(
                    "Metsaregister WFS feature is missing identity property set " + config.identityProperty()
                        + " in " + config.layerName());
            }
            if (sourceId.length() > 320) {
                throw new ExternalRegistryService.RegistryException(
                    "Metsaregister WFS identity exceeds source mirror key limit in " + config.layerName());
            }
            String propertiesJson = json(feature.path("properties"));
            JsonNode geometry = feature.path("geometry");
            String geometryJson = geometry.isObject() ? json(geometry) : null;
            String sourceHash = payloadPolicy.sourceHash(feature);

            dsl.execute("""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                VALUES (?, ?, ?, ?, ?, ?::jsonb,
                    CASE WHEN CAST(? AS text) IS NULL THEN NULL
                         ELSE ST_Transform(
                             ST_SetSRID(ST_Force2D(ST_GeomFromGeoJSON(CAST(? AS text))), 4326),
                             3301
                         )
                    END)
                """, generation.id(), config.layerName(), sourceId, sourceId, sourceHash, propertiesJson,
                geometryJson, geometryJson);
        }

        int pages = generation.pagesProcessed() + 1;
        long rows = generation.rowsStaged() + page.records();
        if (page.complete() && providerMatched != null && rows != providerMatched) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS completed with " + rows + " rows but provider reported " + providerMatched);
        }
        dsl.update(table("metsaregister_wfs_generation"))
            .set(field(name("next_start_index"), Long.class), page.nextStartIndex())
            .set(field(name("pages_processed"), Integer.class), pages)
            .set(field(name("rows_staged"), Long.class), rows)
            .set(field(name("provider_number_matched"), Long.class), providerMatched)
            .set(field(name("last_failure_at"), Instant.class), (Instant) null)
            .set(field(name("last_error"), String.class), (String) null)
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("id"), UUID.class).eq(generation.id())
                .and(field(name("state"), String.class).eq("STAGING")))
            .execute();
        return new Generation(generation.id(), config.layerName(), page.nextStartIndex(), pages, rows, providerMatched);
    }

    private static String identityValue(JsonNode featureNode, String identityDescriptor) {
        List<String> identityFields = Arrays.stream(identityDescriptor.split(",", -1))
            .map(String::trim)
            .toList();
        JsonNode propertiesNode = featureNode.path("properties");
        if (identityFields.isEmpty()) return null;
        if (identityFields.size() == 1) return scalarIdentityValue(featureNode, propertiesNode, identityFields.getFirst());

        StringBuilder key = new StringBuilder();
        int presentValues = 0;
        for (String identityField : identityFields) {
            String value = scalarIdentityValue(featureNode, propertiesNode, identityField);
            if (value == null) {
                value = "<NULL>";
            } else {
                presentValues++;
            }
            key.append(identityField.length()).append(':').append(identityField)
                .append('=').append(value.length()).append(':').append(value).append(';');
        }
        if (presentValues == 0) return null;
        return key.toString();
    }

    private static String scalarIdentityValue(JsonNode featureNode, JsonNode propertiesNode, String identityField) {
        if ("__feature_id".equals(identityField)) return scalarText(featureNode.path("id"));
        return scalarText(propertiesNode.path(identityField));
    }

    private void publishGeneration(Generation generation) {
        UUID previous = dsl.select(field(name("current_generation_id"), UUID.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(generation.layerName()))
            .fetchOne(field(name("current_generation_id"), UUID.class));
        Instant now = Instant.now();

        dsl.update(table("metsaregister_wfs_generation"))
            .set(field(name("state"), String.class), "PUBLISHED")
            .set(field(name("completed_at"), Instant.class), now)
            .set(field(name("updated_at"), Instant.class), now)
            .where(field(name("id"), UUID.class).eq(generation.id())
                .and(field(name("state"), String.class).eq("STAGING")))
            .execute();

        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("current_generation_id"), UUID.class), generation.id())
            .set(field(name("current_row_count"), Long.class), generation.rowsStaged())
            .set(field(name("provider_number_matched"), Long.class), generation.providerNumberMatched())
            .set(field(name("state"), String.class), "HEALTHY")
            .set(field(name("last_success_at"), Instant.class), now)
            .set(field(name("last_failure_at"), Instant.class), (Instant) null)
            .set(field(name("last_error"), String.class), (String) null)
            .set(field(name("updated_at"), Instant.class), now)
            .where(field(name("layer_name"), String.class).eq(generation.layerName()))
            .execute();

        if (previous != null && !previous.equals(generation.id())) {
            dsl.update(table("metsaregister_wfs_generation"))
                .set(field(name("state"), String.class), "RETIRED")
                .set(field(name("updated_at"), Instant.class), now)
                .where(field(name("id"), UUID.class).eq(previous)
                    .and(field(name("state"), String.class).eq("PUBLISHED")))
                .execute();
        }
    }

    private void markSyncing(String layerName) {
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("state"), String.class), "SYNCING")
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("layer_name"), String.class).eq(layerName))
            .execute();
    }

    private void markFailure(String layerName, UUID generationId, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        message = message.replaceAll("[\\r\\n]+", " ");
        if (message.length() > 1000) message = message.substring(0, 1000);
        Instant now = Instant.now();
        try {
            dsl.update(table("metsaregister_wfs_generation"))
                .set(field(name("last_failure_at"), Instant.class), now)
                .set(field(name("last_error"), String.class), message)
                .set(field(name("updated_at"), Instant.class), now)
                .where(field(name("id"), UUID.class).eq(generationId)
                    .and(field(name("state"), String.class).eq("STAGING")))
                .execute();
            dsl.update(table("metsaregister_wfs_layer"))
                .set(field(name("state"), String.class), "FAILED")
                .set(field(name("last_failure_at"), Instant.class), now)
                .set(field(name("last_error"), String.class), message)
                .set(field(name("updated_at"), Instant.class), now)
                .where(field(name("layer_name"), String.class).eq(layerName))
                .execute();
        } catch (RuntimeException ignored) {
            // Preserve the provider/storage exception that caused the failure.
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode Metsaregister WFS feature", exception);
        }
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    record LayerConfig(String layerName, String identityProperty, String sortProperty) { }
    record Generation(UUID id, String layerName, long nextStartIndex, int pagesProcessed,
                      long rowsStaged, Long providerNumberMatched) { }
    record LayerRun(String layerName, UUID generationId, int pagesThisRun, long rowsThisRun,
                    long rowsStagedTotal, boolean complete, int retriesThisRun) { }
}
