package ee.forestiq.registry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map/metsaregister")
@PreAuthorize("isAuthenticated()")
class MetsaregisterMapGatewayController {
    private static final MediaType MVT = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final MetsaregisterLayerManifestService manifest;
    private final MetsaregisterMapGatewayService gateway;
    private final MetsaregisterForestryGenerationService forestryGenerations;
    private final ForestryTileCatalogService forestryTiles;
    private final ForestryMapTileRepository forestryTileRepository;
    private final MetsaregisterLayerHealthService layerHealth;

    /** Compatibility constructor for focused controller tests. */
    MetsaregisterMapGatewayController(
        MetsaregisterLayerManifestService manifest,
        MetsaregisterMapGatewayService gateway,
        MetsaregisterForestryGenerationService forestryGenerations,
        ForestryTileCatalogService forestryTiles,
        ForestryMapTileRepository forestryTileRepository
    ) {
        this(manifest, gateway, forestryGenerations, forestryTiles, forestryTileRepository,
            new MetsaregisterLayerHealthService());
    }

    @Autowired
    MetsaregisterMapGatewayController(
        MetsaregisterLayerManifestService manifest,
        MetsaregisterMapGatewayService gateway,
        MetsaregisterForestryGenerationService forestryGenerations,
        ForestryTileCatalogService forestryTiles,
        ForestryMapTileRepository forestryTileRepository,
        MetsaregisterLayerHealthService layerHealth
    ) {
        this.manifest = manifest;
        this.gateway = gateway;
        this.forestryGenerations = forestryGenerations;
        this.forestryTiles = forestryTiles;
        this.forestryTileRepository = forestryTileRepository;
        this.layerHealth = layerHealth;
    }

    /**
     * Safe frontend registry. It exposes ForestIQ routes and presentation metadata only;
     * provider URLs, credentials, arbitrary WMS parameters and internal generation members stay server-side.
     */
    @GetMapping("/layers")
    MapLayerRegistry layers() {
        UUID generationId = forestryGenerations.currentGenerationId();
        List<MapLayerDescriptor> layers = manifest.mapLayers().stream()
            .map(layer -> descriptor(layer, generationId))
            .toList();
        return new MapLayerRegistry(manifest.version(), forestryTiles.version(), generationId, layers);
    }

    private MapLayerDescriptor descriptor(MetsaregisterLayerManifestService.Layer layer, UUID generationId) {
        ForestryTileCatalogService.Layer tile = forestryTiles.find(layer.key());
        if (tile != null) {
            boolean available = generationId != null;
            var local = layerHealth.forestryLocalHealth(layer.freshnessTarget());
            String localAvailability = available && "UNKNOWN".equals(local.availability()) ? "AVAILABLE" : local.availability();
            return new MapLayerDescriptor(
                layer.key(), layer.title(), "Metsaregister", "FORESTIQ_FORESTRY",
                layer.primaryMode().name(), "MVT", forestryTiles.sourceLayer(),
                MetsaregisterSpatialContractService.PRESENTATION_CRS,
                layer.sourceCrs(), MetsaregisterSpatialContractService.SOURCE_AXIS_ORDER,
                MetsaregisterSpatialContractService.CANONICAL_STORAGE_CRS,
                MetsaregisterSpatialContractService.PRESENTATION_CRS,
                tile.minZoom(), tile.maxZoom(), layer.defaultOpacity(), layer.order(), false,
                tile.queryable(), false, layer.attribution(), layer.freshnessTarget(),
                tile.authorizationScope(), "GENERATION_IMMUTABLE_PRIVATE_1D", tile.styleKind(),
                generationId, available,
                available
                    ? "/api/v1/map/metsaregister/mvt/" + layer.key() + "/" + generationId + "/{z}/{x}/{y}.pbf"
                    : null,
                localAvailability, local.freshness(), local.sourceDate(), local.publishedAt(),
                "NOT_APPLICABLE", null, null);
        }

        String transport = layer.mapPresentationMode().name();
        boolean configured = layer.mapPresentationMode() == MetsaregisterLayerManifestService.MapPresentationMode.WMS;
        String template = configured
            ? "/api/v1/map/metsaregister/wms/" + layer.key()
                + "?bbox={bbox-epsg-3857}&width=256&height=256&crs=EPSG:3857&format=image/png&zoom={z}"
            : null;
        var external = layerHealth.externalHealth(layer.key());
        return new MapLayerDescriptor(
            layer.key(), layer.title(), "Metsaregister", "METSAREGISTER_EXTERNAL",
            layer.primaryMode().name(), transport, null,
            MetsaregisterSpatialContractService.PRESENTATION_CRS,
            layer.sourceCrs(), MetsaregisterSpatialContractService.SOURCE_AXIS_ORDER,
            MetsaregisterSpatialContractService.CANONICAL_STORAGE_CRS,
            MetsaregisterSpatialContractService.PRESENTATION_CRS,
            layer.minZoom(), layer.maxZoom(), layer.defaultOpacity(), layer.order(), false,
            false, false, layer.attribution(), layer.freshnessTarget(),
            "AUTHENTICATED", "UPSTREAM_RASTER_5M", "RASTER", null, configured, template,
            "NOT_APPLICABLE", null, null, null,
            external.availability(), external.lastCheckedAt(), external.errorCode());
    }

    @GetMapping("/mvt/{layerKey}/{generationId}/{z}/{x}/{y}.pbf")
    ResponseEntity<byte[]> vectorTile(
        @PathVariable String layerKey,
        @PathVariable UUID generationId,
        @PathVariable int z,
        @PathVariable int x,
        @PathVariable int y
    ) {
        ForestryMapTileRepository.TileResult tile = forestryTileRepository.tile(layerKey, generationId, z, x, y);
        String etag = "\"mr-forestry:" + layerKey + ":" + generationId + ":" + z + ":" + x + ":" + y + ":auth\"";
        return ResponseEntity.ok()
            .contentType(MVT)
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
            .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
            .header("X-ForestIQ-Feature-Count", Integer.toString(tile.featureCount()))
            .body(tile.bytes());
    }

    @GetMapping("/wms/{layerKey}")
    ResponseEntity<byte[]> map(
        @PathVariable String layerKey,
        @RequestParam String bbox,
        @RequestParam(defaultValue = "256") int width,
        @RequestParam(defaultValue = "256") int height,
        @RequestParam(defaultValue = "EPSG:3857") String crs,
        @RequestParam(defaultValue = "image/png") String format,
        @RequestParam(required = false) Integer zoom
    ) {
        var image = gateway.getMap(layerKey, bbox, width, height, crs, format, zoom);
        return ResponseEntity.ok()
            .contentType(image.contentType())
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
            .body(image.body());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<GatewayError> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new GatewayError("INVALID_MAP_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(ForestryMapTileRepository.TileBudgetExceededException.class)
    ResponseEntity<GatewayError> tileBudgetExceeded(ForestryMapTileRepository.TileBudgetExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new GatewayError("FORESTRY_TILE_BUDGET_EXCEEDED", exception.getMessage()));
    }

    @ExceptionHandler(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException.class)
    ResponseEntity<GatewayError> upstreamFailure(MetsaregisterMapGatewayService.MetsaregisterMapGatewayException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new GatewayError("METSAREGISTER_MAP_UNAVAILABLE", exception.getMessage()));
    }

    record MapLayerRegistry(
        String manifestVersion,
        String tileCatalogVersion,
        UUID forestryGenerationId,
        List<MapLayerDescriptor> layers
    ) { }

    record MapLayerDescriptor(
        String key,
        String title,
        String provider,
        String category,
        String businessMode,
        String transport,
        String sourceLayer,
        String crs,
        String sourceCrs,
        String sourceAxisOrder,
        String canonicalCrs,
        String presentationCrs,
        int minZoom,
        int maxZoom,
        double defaultOpacity,
        int order,
        boolean defaultVisible,
        boolean queryable,
        boolean mandatory,
        String attribution,
        String freshnessTarget,
        String authorizationScope,
        String cachePolicy,
        String styleKind,
        UUID generationId,
        boolean available,
        String tileUrlTemplate,
        String localAvailability,
        String freshnessState,
        LocalDate sourceDate,
        OffsetDateTime publishedAt,
        String externalAvailability,
        Instant externalLastCheckedAt,
        String externalErrorCode
    ) { }

    record GatewayError(String code, String message) { }
}
