package ee.forestiq.geospatial;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/geospatial")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class GeospatialAdminController {
    private final GeospatialOperationsService operations;
    private final GeospatialManualRefreshExecutor manualRefresh;

    public GeospatialAdminController(GeospatialOperationsService operations,
                                     GeospatialManualRefreshExecutor manualRefresh) {
        this.operations = operations;
        this.manualRefresh = manualRefresh;
    }

    @GetMapping("/sources")
    public List<GeospatialOperationsService.SourceStatus> sources() {
        return operations.sources();
    }

    @GetMapping("/sources/{sourceKey}")
    public GeospatialOperationsService.SourceStatus source(@PathVariable String sourceKey) {
        return operations.source(sourceKey);
    }

    @PostMapping("/sources/{sourceKey}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String sourceKey,
                                                        @RequestBody(required = false) RefreshRequest request,
                                                        Authentication authentication) {
        RefreshRequest safe = request == null ? new RefreshRequest(false, null) : request;
        if (safe.forceSchemaDrift() && (safe.reason() == null || safe.reason().trim().length() < 10)) {
            throw new IllegalArgumentException("Forced schema-drift refresh requires an audit reason of at least 10 characters");
        }
        String actor = authentication == null ? "unknown" : authentication.getName();
        manualRefresh.submit(sourceKey, actor, safe.forceSchemaDrift());
        return ResponseEntity.accepted().body(Map.of(
            "accepted", true,
            "sourceKey", sourceKey,
            "forceSchemaDrift", safe.forceSchemaDrift(),
            "message", "Refresh accepted; progress is visible in Admin → Teenused"));
    }

    public record RefreshRequest(boolean forceSchemaDrift, String reason) { }
}
