package ee.forestiq.management;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.management.DataQualityService.ActionRequest;
import ee.forestiq.management.DataQualityService.AssignRequest;
import ee.forestiq.management.DataQualityService.DataQualityEvent;
import ee.forestiq.management.DataQualityService.DataQualityItem;
import ee.forestiq.management.DataQualityService.QueueResponse;
import ee.forestiq.management.DataQualityService.ScanSummary;

@RestController
@RequestMapping("/api/v1/management/data-quality")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
public class DataQualityController {
    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @PostMapping("/refresh")
    public ScanSummary refresh() {
        return service.refresh();
    }

    @GetMapping
    public QueueResponse queue(
        @RequestParam(required = false) String issueType,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String impact,
        @RequestParam(required = false) String assignedSubject,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return service.queue(issueType, status, impact, assignedSubject, limit);
    }

    @GetMapping("/{issueId}/history")
    public List<DataQualityEvent> history(@PathVariable UUID issueId) {
        return service.history(issueId);
    }

    @PutMapping("/{issueId}/assign")
    public DataQualityItem assign(@PathVariable UUID issueId, @Valid @RequestBody AssignBody request) {
        return service.assign(issueId, new AssignRequest(request.assigneeSubject(), request.reason()));
    }

    @PostMapping("/{issueId}/release")
    public DataQualityItem release(@PathVariable UUID issueId, @Valid @RequestBody ActionBody request) {
        return service.release(issueId, new ActionRequest(request.reason()));
    }

    @PostMapping("/{issueId}/resolve")
    public DataQualityItem resolve(@PathVariable UUID issueId, @Valid @RequestBody ActionBody request) {
        return service.resolve(issueId, new ActionRequest(request.reason()));
    }

    public record AssignBody(
        @NotBlank @Size(max = 100) String assigneeSubject,
        @Size(max = 2000) String reason
    ) {}

    public record ActionBody(@NotBlank @Size(max = 2000) String reason) {}
}
