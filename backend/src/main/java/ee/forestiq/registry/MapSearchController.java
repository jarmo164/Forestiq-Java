package ee.forestiq.registry;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map/search")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
class MapSearchController {
    private final MapSearchService search;

    MapSearchController(MapSearchService search) {
        this.search = search;
    }

    @GetMapping
    ResponseEntity<SearchResponse> search(
        @RequestParam("q") String query,
        @RequestParam(name = "limit", defaultValue = "8") int limit
    ) {
        List<MapSearchService.MapSearchResult> results = search.search(query, limit);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new SearchResponse(results));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<SearchError> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new SearchError("INVALID_MAP_SEARCH", exception.getMessage()));
    }

    @ExceptionHandler(MapSearchService.MapSearchUnavailableException.class)
    ResponseEntity<SearchError> providerUnavailable(MapSearchService.MapSearchUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new SearchError("MAP_SEARCH_PROVIDER_UNAVAILABLE", "Aadressiotsing ei ole praegu saadaval. Täpne katastritunnuse otsing töötab jätkuvalt lokaalselt."));
    }

    record SearchResponse(List<MapSearchService.MapSearchResult> results) { }
    record SearchError(String code, String message) { }
}
