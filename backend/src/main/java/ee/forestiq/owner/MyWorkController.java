package ee.forestiq.owner;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.owner.OwnerDtos.*;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/my-work")
@Tag(name = "My Work", description = "Caller workdesk — owners assigned to the current user")
public class MyWorkController {

    private final MyWorkQueryRepository myWorkQueryRepository;
    private final OrganizationContext orgCtx;

    public MyWorkController(MyWorkQueryRepository myWorkQueryRepository, OrganizationContext orgCtx) {
        this.myWorkQueryRepository = myWorkQueryRepository;
        this.orgCtx = orgCtx;
    }

    @GetMapping
    @Operation(summary = "Search my assigned owners")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public PagedResponse<OwnerListItem> search(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        Pagination.PageRequest pagination = Pagination.normalize(page, size);

        return myWorkQueryRepository.searchAssignedOwners(orgId, subject, search, pagination);
    }

    @PostMapping("/next")
    @Operation(summary = "Get next assigned owner (random)")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public Map<String, String> nextOwner() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();

        UUID ownerId = myWorkQueryRepository.findNextAssignedOwnerId(orgId, subject);

        return Map.of("ownerId", ownerId != null ? ownerId.toString() : "");
    }
}
