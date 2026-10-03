package ee.forestiq.ownership;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ownership-transitions")
@Tag(name = "Ownership transitions", description = "Audited Weasel cadastral ownership-change feed")
public class OwnershipTransitionController {
    private final OwnershipTransitionService service;

    public OwnershipTransitionController(OwnershipTransitionService service) { this.service = service; }

    @PostMapping("/sync")
    @Operation(summary = "Synchronize Weasel ownership transitions for the current organization portfolio")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public OwnershipTransitionService.SyncRun sync() { return service.syncCurrentOrganization(); }

    @GetMapping("/owners/{ownerId}")
    @Operation(summary = "List audited cadastral ownership transitions linked to one owner")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public List<OwnershipTransitionService.OwnershipTransitionEvent> events(@PathVariable UUID ownerId) {
        return service.eventsForOwner(ownerId);
    }
}
