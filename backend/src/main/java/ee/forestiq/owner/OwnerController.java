package ee.forestiq.owner;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.owner.OwnerDtos.*;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/owners")
@Tag(name = "Owners", description = "Organization-scoped forest owner CRM")
public class OwnerController {

    private static final Set<OrganizationRole> OWNER_ASSIGNMENT_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);

    private final OwnerService ownerService;
    private final OrganizationIdentityDirectory identityDirectory;
    private final OrganizationContext organizationContext;

    public OwnerController(OwnerService ownerService, OrganizationIdentityDirectory identityDirectory,
                           OrganizationContext organizationContext) {
        this.ownerService = ownerService;
        this.identityDirectory = identityDirectory;
        this.organizationContext = organizationContext;
    }

    @GetMapping
    @Operation(
        summary = "Legacy owner list (retired)",
        description = "Use GET /api/v1/owners/cursor. The OFFSET/count list was retired because it spills and degrades the full dataset under concurrent load.",
        deprecated = true)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public PagedResponse<OwnerListItem> search(
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        throw new ResponseStatusException(HttpStatus.GONE,
            "Legacy owner list retired; use GET /api/v1/owners/cursor");
    }

    @GetMapping("/status-definitions")
    @Operation(summary = "List owner status definitions for CRM workspaces")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public List<OwnerStatusDefinition> statusDefinitions() {
        return ownerService.getStatusDefinitions();
    }

    @GetMapping("/{ownerId}/allowed-status-transitions")
    @Operation(summary = "List manual owner status transitions allowed for the current user and owner state")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public List<OwnerStatusDefinition> allowedStatusTransitions(@PathVariable UUID ownerId) {
        return ownerService.allowedManualStatusTransitions(ownerId);
    }

    @GetMapping("/{ownerId}")
    @Operation(summary = "Get owner profile")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public OwnerDetails get(@PathVariable UUID ownerId) {
        return ownerService.get(ownerId);
    }

    @PostMapping
    @Operation(summary = "Create new owner")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public OwnerDetails create(@Valid @RequestBody CreateOwnerRequest request) {
        return ownerService.create(request);
    }

    @PutMapping("/{ownerId}")
    @Operation(summary = "Update owner profile")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public OwnerDetails update(@PathVariable UUID ownerId, @Valid @RequestBody UpdateOwnerRequest request) {
        return ownerService.update(ownerId, request);
    }

    @PatchMapping("/{ownerId}/status")
    @Operation(summary = "Change owner status outside workflow-managed evaluation states")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public OwnerDetails changeStatus(@PathVariable UUID ownerId, @Valid @RequestBody ChangeStatusRequest request) {
        if (OwnerStatus.EVALUATION.equals(request.status()) || OwnerStatus.WAITS_FOR_EVALUATION.equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Hindamise olek tekib ainult tehingujuhtumi kvalifitseerimise kaudu. Loo või uuenda deal/evaluation workflow.");
        }
        if (request.assignee() != null && !request.assignee().isBlank()) {
            identityDirectory.requireAssignableUser(organizationContext.requiredOrganizationId(),
                request.assignee(), OWNER_ASSIGNMENT_ROLES);
        }
        return ownerService.changeStatus(ownerId, request);
    }

    @PostMapping("/{ownerId}/followings")
    @Operation(summary = "Follow owner")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFollowing(@PathVariable UUID ownerId) {
        ownerService.addFollowing(ownerId, null);
    }

    @DeleteMapping("/{ownerId}/followings")
    @Operation(summary = "Unfollow owner")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFollowing(@PathVariable UUID ownerId) {
        ownerService.removeFollowing(ownerId);
    }

    @PostMapping("/{ownerId}/activities")
    @Operation(summary = "Add note/activity to owner")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addActivity(@PathVariable UUID ownerId, @Valid @RequestBody AddActivityRequest request) {
        ownerService.addNote(ownerId, request.content());
    }

    @PostMapping("/{ownerId}/parcels/mark")
    @Operation(summary = "Mark interesting parcels")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markParcels(@PathVariable UUID ownerId, @Valid @RequestBody MarkParcelsRequest request) {
        ownerService.markParcels(ownerId, request.cadastralCodes());
    }

    public record AddActivityRequest(@NotBlank @Size(max = 4000) String content) {}
    public record MarkParcelsRequest(@NotEmpty List<@NotNull @Size(max = 100) String> cadastralCodes) {}
}
