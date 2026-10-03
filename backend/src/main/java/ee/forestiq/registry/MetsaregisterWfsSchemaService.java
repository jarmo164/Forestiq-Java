package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;

/**
 * Inspects approved provider schemas and freezes the schema contract used by
 * deterministic full-layer paging. A changed provider schema is surfaced as
 * drift instead of being silently accepted by an importer.
 */
@Service
class MetsaregisterWfsSchemaService {
    private static final String FEATURE_ID_FIELD = "__feature_id";
    private static final List<String> IDENTITY_CANDIDATES = List.of("sys_id", "id", "objectid", "gid", "ogc_fid");
    private static final Map<String, List<String>> COMPOSITE_IDENTITIES = Map.of(
        "metsaregister:isearasused", List.of("eraldis_id", "kood"),
        "metsaregister:kahjustused", List.of("eraldis_id", "puuliigi_kood", "kvanus", "pohjuse_kood", "osakaal", "rinde_kood", FEATURE_ID_FIELD),
        "metsaregister:kl_kasvukoht", List.of("kood"),
        "metsaregister:kl_omandivorm", List.of("kood"),
        "metsaregister:kl_puuliik", List.of("kood"),
        "metsaregister:kl_rinne", List.of("kood"),
        "metsaregister:mr__teema_raie_diameeter", List.of("sys_id", FEATURE_ID_FIELD),
        "metsaregister:mr__teema_raie_liik", List.of("sys_id", FEATURE_ID_FIELD),
        "metsaregister:mr__teema_raie_taius", List.of("sys_id", FEATURE_ID_FIELD),
        "metsaregister:mr__teema_raie_vanus", List.of("sys_id", FEATURE_ID_FIELD)
    );

    private final DSLContext dsl;
    private final MetsaregisterWfsMetadataClient metadataClient;
    private final ObjectMapper objectMapper;

    MetsaregisterWfsSchemaService(DSLContext dsl, MetsaregisterWfsMetadataClient metadataClient,
                                  ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.metadataClient = metadataClient;
        this.objectMapper = objectMapper;
    }

    InspectionResult inspectApprovedLayers() {
        List<String> layers = dsl.select(field(name("layer_name"), String.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("approved"), Boolean.class).isTrue()
                .and(field(name("enabled"), Boolean.class).isTrue())
                .and(field(name("provider_present"), Boolean.class).isTrue()))
            .orderBy(field(name("layer_name")).asc())
            .fetch(field(name("layer_name"), String.class));

        int ready = 0;
        int unsupported = 0;
        int drifted = 0;
        int failed = 0;
        for (String layer : layers) {
            try {
                LayerState state = inspectOne(layer);
                switch (state) {
                    case READY -> ready++;
                    case UNSUPPORTED -> unsupported++;
                    case SCHEMA_DRIFT -> drifted++;
                }
            } catch (RuntimeException exception) {
                failed++;
                markFailure(layer, exception);
            }
        }
        return new InspectionResult(layers.size(), ready, unsupported, drifted, failed);
    }

    private LayerState inspectOne(String layerName) {
        MetsaregisterWfsMetadataClient.FeatureSchema schema = metadataClient.describeFeatureType(layerName);
        String schemaJson = schemaJson(schema);
        String fingerprint = sha256(schemaJson);
        var current = dsl.select(
                field(name("schema_fingerprint"), String.class),
                field(name("state"), String.class),
                field(name("current_generation_id"), UUID.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(layerName))
            .fetchOne();
        String previousFingerprint = current == null ? null : current.value1();
        String previousState = current == null ? null : current.value2();
        UUID currentGenerationId = current == null ? null : current.value3();

        if (previousFingerprint != null && !previousFingerprint.equals(fingerprint)) {
            dsl.update(table("metsaregister_wfs_layer"))
                .set(field(name("state"), String.class), "SCHEMA_DRIFT")
                .set(field(name("last_error"), String.class),
                    "DescribeFeatureType changed; detected fingerprint=" + fingerprint)
                .set(field(name("updated_at"), Instant.class), Instant.now())
                .where(field(name("layer_name"), String.class).eq(layerName))
                .execute();
            return LayerState.SCHEMA_DRIFT;
        }

        String identity = chooseIdentity(layerName, schema);
        String sort = identity == null ? null : sortDescriptor(identity);
        LayerState state = identity == null ? LayerState.UNSUPPORTED : LayerState.READY;
        if (sort == null) state = LayerState.UNSUPPORTED;
        String error = state == LayerState.UNSUPPORTED
            ? "No approved deterministic sort/identity property set found in DescribeFeatureType"
            : null;
        String persistedState = state.name();
        if (state == LayerState.READY && currentGenerationId != null && "HEALTHY".equals(previousState)) {
            persistedState = "HEALTHY";
        }

        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("geometry_property"), String.class), schema.geometryProperty())
            .set(field(name("geometry_type"), String.class), schema.geometryType())
            .set(field(name("identity_property"), String.class), identity)
            .set(field(name("sort_property"), String.class), sort)
            .set(field(name("schema_fingerprint"), String.class), fingerprint)
            .set(field(name("schema_definition"), JSONB.class), JSONB.valueOf(schemaJson))
            .set(field(name("state"), String.class), persistedState)
            .set(field(name("last_error"), String.class), error)
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("layer_name"), String.class).eq(layerName))
            .execute();
        return state;
    }

    private String chooseIdentity(String layerName, MetsaregisterWfsMetadataClient.FeatureSchema schema) {
        Map<String, String> available = new HashMap<>();
        for (var property : schema.properties()) {
            if (!property.geometry()) {
                available.put(property.name().toLowerCase(Locale.ROOT), property.name());
            }
        }
        for (String candidate : IDENTITY_CANDIDATES) {
            String actual = available.get(candidate);
            if (actual != null) return actual;
        }

        List<String> configured = COMPOSITE_IDENTITIES.get(layerName);
        if (configured == null) return null;
        List<String> actualFields = new ArrayList<>(configured.size());
        for (String required : configured) {
            String actual = FEATURE_ID_FIELD.equals(required) ? FEATURE_ID_FIELD : available.get(required.toLowerCase(Locale.ROOT));
            if (actual == null) return null;
            actualFields.add(actual);
        }
        return String.join(",", actualFields);
    }

    private static String sortDescriptor(String identityDescriptor) {
        List<String> sortFields = Arrays.stream(identityDescriptor.split(",", -1))
            .map(String::trim)
            .filter(field -> !field.isEmpty())
            .filter(field -> !field.startsWith("__"))
            .toList();
        return sortFields.isEmpty() ? null : String.join(",", sortFields);
    }

    private String schemaJson(MetsaregisterWfsMetadataClient.FeatureSchema schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Metsaregister WFS schema", exception);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void markFailure(String layerName, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        message = message.replaceAll("[\\r\\n]+", " ");
        if (message.length() > 1000) message = message.substring(0, 1000);
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("state"), String.class), "FAILED")
            .set(field(name("last_failure_at"), Instant.class), Instant.now())
            .set(field(name("last_error"), String.class), message)
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("layer_name"), String.class).eq(layerName))
            .execute();
    }

    enum LayerState { READY, UNSUPPORTED, SCHEMA_DRIFT }
    record InspectionResult(int inspected, int ready, int unsupported, int schemaDrift, int failed) { }
}
