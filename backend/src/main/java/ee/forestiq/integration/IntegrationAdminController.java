package ee.forestiq.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.owner.Pagination;

@RestController
@RequestMapping("/api/v1/admin/integrations")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class IntegrationAdminController {
    private final IntegrationJobService service;

    public IntegrationAdminController(IntegrationJobService service) { this.service = service; }

    @GetMapping
    public List<IntegrationJobService.JobView> jobs() { return service.jobsForCurrentOrganization(); }

    @PostMapping("/{key}/runs")
    public ResponseEntity<IntegrationJobService.RunView> start(
        @PathVariable IntegrationJobKey key,
        @RequestBody(required = false) StartRunRequest request
    ) {
        Map<String, String> parameters = request == null || request.parameters() == null ? Map.of() : request.parameters();
        return ResponseEntity.accepted().body(service.startManual(key, parameters));
    }

    @GetMapping("/{key}/runs")
    public List<IntegrationJobService.RunView> recent(@PathVariable IntegrationJobKey key,
                                                      @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Pagination.normalize(0, limit).size();
        return service.recent(key, safeLimit);
    }

    @GetMapping("/runs/{runId}")
    public IntegrationJobService.RunView run(@PathVariable UUID runId) { return service.run(runId); }

    public record StartRunRequest(Map<String, String> parameters) { }
}
