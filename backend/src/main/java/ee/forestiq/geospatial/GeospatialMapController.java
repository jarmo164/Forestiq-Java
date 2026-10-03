package ee.forestiq.geospatial;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
@PreAuthorize("isAuthenticated()")
public class GeospatialMapController {
    private final ParcelGeospatialContextService parcelContext;
    private final GeospatialOverlayService overlays;

    public GeospatialMapController(ParcelGeospatialContextService parcelContext,
                                   GeospatialOverlayService overlays) {
        this.parcelContext = parcelContext;
        this.overlays = overlays;
    }

    @GetMapping("/land-parcels/{cadastralCode}/context")
    public ParcelGeospatialContextService.ParcelContext context(@PathVariable String cadastralCode) {
        return parcelContext.context(cadastralCode);
    }

    @GetMapping("/context/layers")
    public List<GeospatialOverlayService.LayerDescriptor> layers() {
        return overlays.layers();
    }

    @GetMapping(value = "/context/mvt/{sourceKey}/{generationId}/{z}/{x}/{y}.pbf",
        produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> tile(@PathVariable String sourceKey,
                                       @PathVariable UUID generationId,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y) {
        byte[] body = overlays.tile(sourceKey, generationId, z, x, y);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePrivate().immutable())
            .varyBy("Authorization")
            .contentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"))
            .body(body);
    }
}
