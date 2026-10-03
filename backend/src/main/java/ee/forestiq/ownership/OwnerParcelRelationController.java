package ee.forestiq.ownership;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.ownership.OwnerParcelRelationService.AuditView;
import ee.forestiq.ownership.OwnerParcelRelationService.RelationView;
import ee.forestiq.ownership.OwnerParcelRelationService.Resolution;

@RestController
@RequestMapping("/api/v1/ownership-relations")
@Tag(name = "Ownership relations", description = "Auditable owner-to-cadastral parcel relationship management")
public class OwnerParcelRelationController {

    private final OwnerParcelRelationService service;

    public OwnerParcelRelationController(OwnerParcelRelationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
        summary = "Legacy owner-parcel relation list (retired)",
        description = "Use GET /api/v1/ownership-relations/cursor. The legacy list was retired because it creates unbounded work and temp-I/O at full dataset scale.",
        deprecated = true)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public List<RelationView> search(
        @RequestParam(defaultValue = "") String search,
        @RequestParam(required = false) Boolean active,
        @RequestParam(defaultValue = "") String source,
        @RequestParam(defaultValue = "100") int limit) {
        throw new ResponseStatusException(HttpStatus.GONE,
            "Legacy ownership relation list retired; use GET /api/v1/ownership-relations/cursor");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create or reactivate a protected manual owner-parcel relation")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RelationView create(@Valid @RequestBody CreateRelationRequest request) {
        return service.createManualRelation(request.ownerId(), request.cadastralCode(), request.reason());
    }

    @PostMapping("/{ownerId}/{parcelId}/end")
    @Operation(summary = "End an owner-parcel relation without deleting its history")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RelationView end(@PathVariable UUID ownerId, @PathVariable UUID parcelId,
                            @Valid @RequestBody ReasonRequest request) {
        return service.endManualRelation(ownerId, parcelId, request.reason());
    }

    @GetMapping("/{ownerId}/{parcelId}/audit")
    @Operation(summary = "Read the immutable manual relationship audit trail")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public List<AuditView> audit(@PathVariable UUID ownerId, @PathVariable UUID parcelId) {
        return service.audit(ownerId, parcelId);
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    @Operation(summary = "Resolve a provider-vs-manual relationship conflict")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RelationView resolve(@PathVariable UUID conflictId, @Valid @RequestBody ResolveConflictRequest request) {
        return service.resolveConflict(conflictId, request.resolution(), request.reason());
    }

    public record CreateRelationRequest(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 100) String cadastralCode,
        @NotBlank @Size(max = 2000) String reason) { }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) { }

    public record ResolveConflictRequest(
        @NotNull Resolution resolution,
        @NotBlank @Size(max = 2000) String reason) { }
}
