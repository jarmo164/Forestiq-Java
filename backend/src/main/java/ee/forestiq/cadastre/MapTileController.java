package ee.forestiq.cadastre;

import java.time.Duration;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/map")
public class MapTileController {

    static final int OVERVIEW_MAX_ZOOM = 10;
    private static final MediaType MVT = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final MapTileService mapTileService;
    private final MapTileLimitService mapTileLimitService;

    @Autowired
    public MapTileController(MapTileService mapTileService, MapTileLimitService mapTileLimitService) {
        this.mapTileService = mapTileService;
        this.mapTileLimitService = mapTileLimitService;
    }

    /** Compatibility constructor retained for focused non-MVC tests. */
    MapTileController(MapTileService mapTileService) {
        this(mapTileService, null);
    }

    @GetMapping("/generation")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ResponseEntity<MapTileService.MapGenerationInfo> generation() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(mapTileService.generationInfo());
    }

    @GetMapping("/land-parcels-overview/{z}/{x}/{y}.pbf")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ResponseEntity<byte[]> landParcelsOverview(
        @PathVariable int z,
        @PathVariable int x,
        @PathVariable int y,
        @RequestParam(name = "v", defaultValue = "0") long dataVersion,
        @RequestParam(name = "generation", required = false) String generationKey
    ) {
        validateTile(z, x, y);
        if (z > OVERVIEW_MAX_ZOOM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Overview tiles are only available through zoom 10");
        }
        byte[] tile = generationKey == null
            ? mapTileService.landParcelsOverview(z, x, y, dataVersion)
            : mapTileService.landParcelsOverviewForGeneration(z, x, y, generationKey);
        return tileResponse(tile);
    }

    @GetMapping("/land-parcels/{z}/{x}/{y}.pbf")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ResponseEntity<byte[]> landParcels(
        @PathVariable int z,
        @PathVariable int x,
        @PathVariable int y,
        @RequestParam(name = "v", defaultValue = "0") long dataVersion,
        @RequestParam(name = "generation", required = false) String generationKey
    ) {
        validateTile(z, x, y);
        byte[] tile = generationKey == null
            ? mapTileService.landParcels(z, x, y, dataVersion)
            : mapTileService.landParcelsForGeneration(z, x, y, generationKey);
        return tileResponse(tile);
    }

    @GetMapping("/land-parcels/{z}/{x}/{y}/meta")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ResponseEntity<MapTileLimitService.TileLimitInfo> landParcelsMeta(
        @PathVariable int z,
        @PathVariable int x,
        @PathVariable int y,
        @RequestParam(name = "generation", required = false) String generationKey
    ) {
        validateTile(z, x, y);
        if (mapTileLimitService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tile metadata service is unavailable");
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(mapTileLimitService.landParcels(z, x, y, generationKey));
    }

    /** Compatibility overload used by focused unit tests and non-MVC callers. */
    ResponseEntity<byte[]> landParcels(int z, int x, int y) {
        return landParcels(z, x, y, 0L, null);
    }

    /** Returns the small commercial read model needed immediately after map selection. */
    @GetMapping("/land-parcels/{cadastralCode}/summary")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ParcelMapSummary landParcelSummary(@PathVariable String cadastralCode) {
        return mapTileService.landParcelSummary(cadastralCode);
    }

    private ResponseEntity<byte[]> tileResponse(byte[] tile) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate().mustRevalidate())
            .eTag(etag(tile))
            .contentType(MVT)
            .body(tile);
    }

    static String etag(byte[] tile) {
        return "\"" + Integer.toUnsignedString(Arrays.hashCode(tile), 16) + "-" + tile.length + "\"";
    }

    private static void validateTile(int z, int x, int y) {
        if (z < 0 || z > 22) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid zoom");
        }
        long max = 1L << z;
        if (x < 0 || y < 0 || x >= max || y >= max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tile coordinates");
        }
    }
}
