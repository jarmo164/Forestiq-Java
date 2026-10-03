package ee.forestiq.cadastre;

import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

/**
 * Bounded metadata probe for the active parcel tile. This deliberately counts at most
 * featureLimit + 1 rows so warning detection cannot turn into an unbounded viewport query.
 */
@Service
public class MapTileLimitService {
    private static final String CADASTRE_SOURCE = "MARU_CADASTRE";

    private final DSLContext dsl;
    private final GenerationMapTileRepository repository;
    private final OrganizationContext organizationContext;
    private final OwnerAuthorizationService ownerAuthorizationService;

    public MapTileLimitService(DSLContext dsl,
                               GenerationMapTileRepository repository,
                               OrganizationContext organizationContext,
                               OwnerAuthorizationService ownerAuthorizationService) {
        this.dsl = dsl;
        this.repository = repository;
        this.organizationContext = organizationContext;
        this.ownerAuthorizationService = ownerAuthorizationService;
    }

    public TileLimitInfo landParcels(int z, int x, int y, String requestedGenerationKey) {
        UUID generationId = repository.currentCadastreGenerationId();
        if (generationId == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No published cadastral generation is available");
        }
        validateGenerationKey(requestedGenerationKey, generationId);

        UUID orgId = organizationContext.requiredOrganizationId();
        Optional<String> restriction = ownerAuthorizationService.assignedSubjectRestriction();
        boolean restricted = restriction.isPresent();
        String scopeKey = restriction.map(subject -> "SUB:" + subject).orElse("ALL");
        int featureLimit = GenerationMapTileRepository.maxFeatures(z);

        var result = dsl.fetchOne("""
            WITH tile AS (
                SELECT ST_Transform(ST_TileEnvelope(?::int, ?::int, ?::int), 3301) AS envelope_3301
            ),
            bounded_candidates AS (
                SELECT 1
                  FROM geospatial_cadastre_parcel gp
                  JOIN land_parcel p
                    ON p.organization_id = ?
                   AND p.cadastral_code = gp.cadastral_code
                  LEFT JOIN land_parcel_render_owner current_owner
                    ON current_owner.organization_id = p.organization_id
                   AND current_owner.parcel_id = p.id
                   AND current_owner.scope_key = ?
                  CROSS JOIN tile
                 WHERE gp.source_key = ?
                   AND gp.generation_id = ?
                   AND (? = false OR current_owner.owner_id IS NOT NULL)
                   AND gp.geom && tile.envelope_3301
                 LIMIT ?
            )
            SELECT count(*)::int AS observed_features
              FROM bounded_candidates
            """, z, x, y, orgId, scopeKey, CADASTRE_SOURCE, generationId, restricted, featureLimit + 1);

        Integer observedValue = result == null ? null : result.get("observed_features", Integer.class);
        return limitInfo(z, observedValue == null ? 0 : observedValue);
    }

    static TileLimitInfo limitInfo(int zoom, int observedFeatures) {
        int featureLimit = GenerationMapTileRepository.maxFeatures(zoom);
        return new TileLimitInfo(
            "land_parcel",
            zoom,
            featureLimit,
            Math.max(0, observedFeatures),
            observedFeatures > featureLimit);
    }

    private static void validateGenerationKey(String requestedGenerationKey, UUID generationId) {
        if (requestedGenerationKey == null || requestedGenerationKey.isBlank()) return;
        String expected = generationId + ":r" + MapTileService.REPRESENTATION_VERSION;
        if (!expected.equals(requestedGenerationKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Requested map generation is no longer current");
        }
    }

    public record TileLimitInfo(
        String layer,
        int zoom,
        int featureLimit,
        int observedFeatures,
        boolean truncated
    ) { }
}
