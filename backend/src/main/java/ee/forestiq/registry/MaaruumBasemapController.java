package ee.forestiq.registry;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map/basemaps")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
class MaaruumBasemapController {
    private final MaaruumBasemapService basemaps;

    MaaruumBasemapController(MaaruumBasemapService basemaps) {
        this.basemaps = basemaps;
    }

    @GetMapping
    ResponseEntity<BasemapCatalog> catalog() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .body(new BasemapCatalog("2026-09-07", basemaps.descriptors()));
    }

    @GetMapping("/{key}/{z}/{x}/{y}")
    ResponseEntity<byte[]> tile(
        @PathVariable String key,
        @PathVariable int z,
        @PathVariable int x,
        @PathVariable int y
    ) {
        MaaruumBasemapService.BasemapTile tile = basemaps.tile(key, z, x, y);
        return ResponseEntity.ok()
            .contentType(tile.contentType())
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600, stale-if-error=86400")
            .body(tile.body());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<BasemapError> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new BasemapError("INVALID_BASEMAP_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(MaaruumBasemapService.BasemapUnavailableException.class)
    ResponseEntity<BasemapError> upstreamFailure(MaaruumBasemapService.BasemapUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new BasemapError("BASEMAP_PROVIDER_UNAVAILABLE", "Maa- ja Ruumiameti aluskaart ei ole praegu saadaval."));
    }

    record BasemapCatalog(String version, List<MaaruumBasemapService.BasemapDescriptor> layers) { }
    record BasemapError(String code, String message) { }
}
