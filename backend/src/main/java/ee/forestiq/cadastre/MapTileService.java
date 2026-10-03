package ee.forestiq.cadastre;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.map.MapTileAdmissionGate;
import ee.forestiq.map.MapTileAdmissionGate.Family;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

/** Application-facing orchestration for organization-scoped, generation-backed map reads. */
@Service
public class MapTileService {
    private static final long TILE_CACHE_MAX_WEIGHT_BYTES = 256L * 1024L * 1024L;
    private static final Duration TILE_CACHE_TTL = Duration.ofSeconds(60);
    static final int REPRESENTATION_VERSION = 3;

    private final GenerationMapTileRepository repository;
    private final OrganizationContext organizationContext;
    private final OwnerAuthorizationService ownerAuthorizationService;
    private final MeterRegistry meterRegistry;
    private final MapTileAdmissionGate admission;
    private final Cache<TileCacheKey, byte[]> tileCache;

    @Autowired
    public MapTileService(GenerationMapTileRepository repository,
                          OrganizationContext organizationContext,
                          OwnerAuthorizationService ownerAuthorizationService,
                          MeterRegistry meterRegistry,
                          MapTileAdmissionGate admission) {
        this.repository = repository;
        this.organizationContext = organizationContext;
        this.ownerAuthorizationService = ownerAuthorizationService;
        this.meterRegistry = meterRegistry;
        this.admission = admission;
        this.tileCache = Caffeine.newBuilder()
            .maximumWeight(TILE_CACHE_MAX_WEIGHT_BYTES)
            .weigher((TileCacheKey ignored, byte[] value) -> Math.min(Integer.MAX_VALUE, value.length + 128))
            .expireAfterWrite(TILE_CACHE_TTL)
            .recordStats()
            .build();

        meterRegistry.gauge("forestiq.map.tile.cache.entries", tileCache, Cache::estimatedSize);
    }

    /** Focused-test compatibility constructor. */
    public MapTileService(GenerationMapTileRepository repository,
                          OrganizationContext organizationContext,
                          OwnerAuthorizationService ownerAuthorizationService,
                          MeterRegistry meterRegistry) {
        this(repository, organizationContext, ownerAuthorizationService, meterRegistry,
            new MapTileAdmissionGate(meterRegistry));
    }

    /** Compatibility overload for non-HTTP callers and focused tests. */
    public byte[] landParcels(int z, int x, int y) {
        return landParcels(z, x, y, 0L);
    }

    /** Legacy compatibility path. dataVersion never controls source selection/cache identity. */
    public byte[] landParcels(int z, int x, int y, long dataVersion) {
        return landParcelsForGeneration(z, x, y, null);
    }

    /** HTTP map path: a supplied key must identify exactly the currently published representation. */
    public byte[] landParcelsForGeneration(int z, int x, int y, String requestedGenerationKey) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Optional<String> restriction = ownerAuthorizationService.assignedSubjectRestriction();
        UUID generationId = requiredPublishedGeneration();
        validateGenerationKey(requestedGenerationKey, generationId);
        return protectedTile("land_parcel", orgId, restriction, z, x, y, generationId,
            () -> repository.landParcelsTile(orgId, restriction, generationId, z, x, y));
    }

    public byte[] landParcelsOverview(int z, int x, int y, long dataVersion) {
        return landParcelsOverviewForGeneration(z, x, y, null);
    }

    public byte[] landParcelsOverviewForGeneration(int z, int x, int y, String requestedGenerationKey) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Optional<String> restriction = ownerAuthorizationService.assignedSubjectRestriction();
        UUID generationId = requiredPublishedGeneration();
        validateGenerationKey(requestedGenerationKey, generationId);
        return protectedTile("land_parcel_overview", orgId, restriction, z, x, y, generationId,
            () -> repository.landParcelsOverviewTile(orgId, restriction, generationId, z, x, y));
    }

    public MapGenerationInfo generationInfo() {
        UUID generationId = repository.currentCadastreGenerationId();
        return new MapGenerationInfo(generationId, REPRESENTATION_VERSION, generationId != null);
    }

    private UUID requiredPublishedGeneration() {
        UUID generationId = repository.currentCadastreGenerationId();
        if (generationId == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "No published cadastral generation is available");
        }
        return generationId;
    }

    private static void validateGenerationKey(String requestedGenerationKey, UUID generationId) {
        if (requestedGenerationKey == null || requestedGenerationKey.isBlank()) return;
        String expected = generationId + ":r" + REPRESENTATION_VERSION;
        if (!expected.equals(requestedGenerationKey)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Requested map generation is no longer current");
        }
    }

    private byte[] protectedTile(String layer,
                                 UUID orgId,
                                 Optional<String> restriction,
                                 int z,
                                 int x,
                                 int y,
                                 UUID generationId,
                                 Supplier<byte[]> loader) {
        String visibilityScope = restriction.orElse("");
        TileCacheKey cacheKey = new TileCacheKey(
            layer, orgId, visibilityScope, z, x, y, generationId, REPRESENTATION_VERSION);

        byte[] cached = tileCache.getIfPresent(cacheKey);
        if (cached != null) {
            meterRegistry.counter("forestiq.map.tile.cache.requests", "layer", layer, "result", "hit").increment();
            recordTileSize(layer, z, cached.length);
            return cached;
        }

        meterRegistry.counter("forestiq.map.tile.cache.requests", "layer", layer, "result", "miss").increment();
        AtomicBoolean loadedByThisRequest = new AtomicBoolean(false);
        byte[] tile = tileCache.get(cacheKey, ignored -> {
            loadedByThisRequest.set(true);
            return generateTile(layer, z, loader);
        });
        if (!loadedByThisRequest.get()) {
            meterRegistry.counter("forestiq.map.tile.cache.requests", "layer", layer, "result", "coalesced").increment();
        }

        recordTileSize(layer, z, tile.length);
        return tile;
    }

    private byte[] generateTile(String layer, int z, Supplier<byte[]> loader) {
        Timer timer = Timer.builder("forestiq.map.tile.latency")
            .description("Protected vector tile generation latency")
            .tag("layer", layer)
            .tag("zoom", Integer.toString(z))
            .register(meterRegistry);
        try {
            byte[] loaded = timer.record(() -> admission.execute(Family.CADASTRE, loader));
            byte[] tile = loaded == null ? new byte[0] : loaded;
            int budget = tileBudgetBytes(z);
            if (tile.length > budget) {
                meterRegistry.counter("forestiq.map.tile.byte_budget_exceeded",
                    "layer", layer,
                    "zoom_band", zoomBand(z)).increment();
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Vector tile exceeds the configured byte budget");
            }
            meterRegistry.counter("forestiq.map.tile.cache.loads", "layer", layer, "result", "success").increment();
            return tile;
        } catch (RuntimeException ex) {
            meterRegistry.counter("forestiq.map.tile.cache.loads", "layer", layer, "result", "failure").increment();
            throw ex;
        }
    }

    static int tileBudgetBytes(int zoom) {
        if (zoom <= 8) return 256 * 1024;
        if (zoom <= 10) return 384 * 1024;
        if (zoom <= 12) return 768 * 1024;
        return 1024 * 1024;
    }

    private static String zoomBand(int zoom) {
        if (zoom <= 8) return "low";
        if (zoom <= 10) return "mid";
        if (zoom <= 12) return "detail";
        return "high-detail";
    }

    private void recordTileSize(String layer, int z, int bytes) {
        DistributionSummary.builder("forestiq.map.tile.bytes")
            .description("Protected vector tile response size")
            .baseUnit("bytes")
            .tag("layer", layer)
            .tag("zoom", Integer.toString(z))
            .register(meterRegistry)
            .record(bytes);
    }

    long tileCacheEstimatedSize() {
        return tileCache.estimatedSize();
    }

    public ParcelMapSummary landParcelSummary(String cadastralCode) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Optional<String> assignedSubjectRestriction = ownerAuthorizationService.assignedSubjectRestriction();
        UUID generationId = requiredPublishedGeneration();
        return repository.landParcelSummary(orgId, assignedSubjectRestriction, generationId, cadastralCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Land parcel not found"));
    }

    public record MapGenerationInfo(UUID generationId, int representationVersion, boolean available) {}

    private record TileCacheKey(String layer,
                                UUID organizationId,
                                String visibilityScope,
                                int z,
                                int x,
                                int y,
                                UUID generationId,
                                int representationVersion) {}
}
