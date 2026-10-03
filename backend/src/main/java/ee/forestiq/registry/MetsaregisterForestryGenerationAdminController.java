package ee.forestiq.registry;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;

@RestController
@RequestMapping("/api/v1/admin/integrations/metsaregister-wfs/forestry-generations")
@PreAuthorize("hasRole('ORG_ADMIN')")
class MetsaregisterForestryGenerationAdminController {
    private final DSLContext dsl;
    private final MetsaregisterForestryGenerationService forestry;
    private final GeospatialSourceGenerationRepository generations;

    MetsaregisterForestryGenerationAdminController(
        DSLContext dsl,
        MetsaregisterForestryGenerationService forestry,
        GeospatialSourceGenerationRepository generations
    ) {
        this.dsl = dsl;
        this.forestry = forestry;
        this.generations = generations;
    }

    @GetMapping
    List<GenerationView> generations() {
        return dsl.fetch("""
            SELECT generation.id,
                   generation.status,
                   generation.source_version,
                   generation.artifact_checksum,
                   generation.row_count,
                   generation.created_at::text AS created_at,
                   generation.published_at::text AS published_at,
                   generation.retired_at::text AS retired_at,
                   count(member.layer_name)::int AS member_count,
                   (current.generation_id IS NOT NULL) AS current
              FROM geospatial_generation generation
              LEFT JOIN metsaregister_forestry_generation_member member
                ON member.forestry_generation_id = generation.id
              LEFT JOIN geospatial_source_current current
                ON current.source_key = generation.source_key
               AND current.generation_id = generation.id
             WHERE generation.source_key = 'METSAREGISTER_FORESTRY'
             GROUP BY generation.id, generation.status, generation.source_version,
                      generation.artifact_checksum, generation.row_count,
                      generation.created_at, generation.published_at, generation.retired_at,
                      current.generation_id
             ORDER BY generation.created_at DESC
            """).map(record -> new GenerationView(
                record.get("id", UUID.class),
                record.get("status", String.class),
                record.get("source_version", String.class),
                record.get("artifact_checksum", String.class),
                record.get("row_count", Long.class),
                record.get("member_count", Integer.class),
                Boolean.TRUE.equals(record.get("current", Boolean.class)),
                record.get("created_at", String.class),
                record.get("published_at", String.class),
                record.get("retired_at", String.class)));
    }

    @GetMapping("/comparison")
    List<ComparisonView> comparison() {
        return dsl.fetch("""
            SELECT layer_name, forestry_generation_id, legacy_generation_id,
                   canonical_layer_generation_id, legacy_row_count, canonical_row_count,
                   generation_matches, row_count_matches
              FROM metsaregister_forestry_cutover_comparison
             ORDER BY layer_name
            """).map(record -> new ComparisonView(
                record.get("layer_name", String.class),
                record.get("forestry_generation_id", UUID.class),
                record.get("legacy_generation_id", UUID.class),
                record.get("canonical_layer_generation_id", UUID.class),
                record.get("legacy_row_count", Long.class),
                record.get("canonical_row_count", Long.class),
                Boolean.TRUE.equals(record.get("generation_matches", Boolean.class)),
                Boolean.TRUE.equals(record.get("row_count_matches", Boolean.class))));
    }

    @PostMapping("/publish")
    GenerationAction publish(Authentication authentication) {
        UUID id = forestry.publishIfChanged(actor(authentication), "Manual canonical forestry publish");
        return new GenerationAction(id, "PUBLISHED_OR_CURRENT");
    }

    @PostMapping("/{generationId}/rollback")
    GenerationAction rollback(@PathVariable UUID generationId, Authentication authentication) {
        generations.rollbackToRetired(
            MetsaregisterForestryGenerationService.SOURCE_KEY,
            generationId,
            actor(authentication),
            "Manual Metsaregister forestry rollback");
        return new GenerationAction(generationId, "ROLLED_BACK");
    }

    private static String actor(Authentication authentication) {
        return authentication == null || authentication.getName() == null
            ? "admin:unknown"
            : authentication.getName();
    }

    record GenerationView(
        UUID generationId,
        String status,
        String sourceVersion,
        String checksum,
        Long rowCount,
        Integer memberCount,
        boolean current,
        String createdAt,
        String publishedAt,
        String retiredAt
    ) { }

    record ComparisonView(
        String layerName,
        UUID forestryGenerationId,
        UUID legacyGenerationId,
        UUID canonicalLayerGenerationId,
        Long legacyRowCount,
        Long canonicalRowCount,
        boolean generationMatches,
        boolean rowCountMatches
    ) { }

    record GenerationAction(UUID generationId, String action) { }
}
