package ee.forestiq.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import ee.forestiq.geospatial.GeospatialSourceGenerationRepository.RunCounters;

/**
 * Publishes one atomic ForestIQ generation over all retained Metsaregister domain layers.
 * Provider-layer generations are immutable members; map/detail readers resolve only through
 * the shared METSAREGISTER_FORESTRY current pointer.
 */
@Service
class MetsaregisterForestryGenerationService {
    static final String SOURCE_KEY = "METSAREGISTER_FORESTRY";

    private final DSLContext dsl;
    private final MetsaregisterLayerManifestService manifest;
    private final GeospatialSourceGenerationRepository generations;

    MetsaregisterForestryGenerationService(
        DSLContext dsl,
        MetsaregisterLayerManifestService manifest,
        GeospatialSourceGenerationRepository generations
    ) {
        this.dsl = dsl;
        this.manifest = manifest;
        this.generations = generations;
    }

    UUID currentGenerationId() {
        return generations.currentGenerationId(SOURCE_KEY);
    }

    /**
     * Publishes the current complete set of LOCAL_DOMAIN_DATA layer snapshots if its composition
     * differs from the currently published bundle. Returning the current UUID is a no-op success.
     */
    UUID publishIfChanged(String actorSubject, String reason) {
        List<LayerGeneration> members = loadCompleteMembers();
        String checksum = checksum(members);

        var current = dsl.fetchOne("""
            SELECT g.id, g.artifact_checksum
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ?
            """, SOURCE_KEY);
        if (current != null && checksum.equals(current.get("artifact_checksum", String.class))) {
            return current.get("id", UUID.class);
        }

        var accepted = dsl.fetchOne("""
            SELECT id, status
              FROM geospatial_generation
             WHERE source_key = ? AND artifact_checksum = ?
               AND status IN ('VALIDATED', 'PUBLISHED', 'RETIRED')
             ORDER BY created_at DESC
             LIMIT 1
            """, SOURCE_KEY, checksum);
        if (accepted != null) {
            UUID acceptedId = accepted.get("id", UUID.class);
            String status = accepted.get("status", String.class);
            if ("RETIRED".equals(status)) {
                generations.rollbackToRetired(SOURCE_KEY, acceptedId, actorSubject,
                    "Republish already validated forestry layer bundle: " + reason);
            } else if ("VALIDATED".equals(status)) {
                generations.publishValidated(SOURCE_KEY, acceptedId, actorSubject, reason);
            }
            return acceptedId;
        }

        UUID runId = generations.startRun(SOURCE_KEY, "SCHEDULED");
        try {
            UUID generationId = generations.createStagingGeneration(
                runId,
                SOURCE_KEY,
                LocalDate.now(),
                "manifest=" + manifest.version(),
                checksum,
                null,
                sha256("manifest:" + manifest.version()),
                4326,
                3301);

            long totalRows = 0;
            for (LayerGeneration member : members) {
                dsl.execute("""
                    INSERT INTO metsaregister_forestry_generation_member
                        (forestry_generation_id, layer_name, layer_generation_id, row_count)
                    VALUES (?, ?, ?, ?)
                    """, generationId, member.layerName(), member.generationId(), member.rowCount());
                totalRows += member.rowCount();
            }

            String validation = "{\"manifestVersion\":\"" + manifest.version()
                + "\",\"memberCount\":" + members.size()
                + ",\"allMembersPublished\":true}";
            generations.markValidated(generationId, totalRows, 0, 0, validation);
            generations.publishValidated(SOURCE_KEY, generationId, actorSubject, reason);
            return generationId;
        } catch (RuntimeException exception) {
            try {
                generations.finishUnpublishedRun(runId, "FAILED", RunCounters.zero(), exception.getMessage());
            } catch (RuntimeException ignored) {
                exception.addSuppressed(ignored);
            }
            throw exception;
        }
    }

    private List<LayerGeneration> loadCompleteMembers() {
        List<String> expected = manifest.approvedLocalLayerNames().stream().sorted().toList();
        if (expected.isEmpty()) {
            throw new IllegalStateException("Metsaregister manifest has no LOCAL_DOMAIN_DATA layers");
        }

        List<LayerGeneration> members = dsl.fetch("""
            SELECT layer_name, current_generation_id, current_row_count
              FROM metsaregister_wfs_layer
             WHERE consumption_mode = 'LOCAL_DOMAIN_DATA'
               AND approved = TRUE
               AND enabled = TRUE
             ORDER BY layer_name
            """).map(record -> new LayerGeneration(
                record.get("layer_name", String.class),
                record.get("current_generation_id", UUID.class),
                record.get("current_row_count", Long.class) == null ? 0L : record.get("current_row_count", Long.class)));

        if (!members.stream().map(LayerGeneration::layerName).toList().equals(expected)) {
            throw new IllegalStateException("Database LOCAL_DOMAIN_DATA policy is not reconciled with manifest " + manifest.version());
        }
        List<String> unavailable = members.stream()
            .filter(member -> member.generationId() == null)
            .map(LayerGeneration::layerName)
            .toList();
        if (!unavailable.isEmpty()) {
            throw new IllegalStateException("Cannot publish forestry generation; layers have no last-known-good generation: " + unavailable);
        }
        return List.copyOf(members);
    }

    private static String checksum(List<LayerGeneration> members) {
        StringBuilder value = new StringBuilder();
        for (LayerGeneration member : members) {
            value.append(member.layerName()).append('=')
                .append(member.generationId()).append(':')
                .append(member.rowCount()).append('\n');
        }
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record LayerGeneration(String layerName, UUID generationId, long rowCount) { }
}
