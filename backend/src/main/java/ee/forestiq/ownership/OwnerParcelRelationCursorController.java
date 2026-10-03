package ee.forestiq.ownership;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.ownership.OwnerParcelRelationCursorService.RelationCursorPage;

@RestController
@RequestMapping("/api/v1/ownership-relations")
@Tag(name = "Ownership relations", description = "Auditable owner-to-cadastral parcel relationship management")
public class OwnerParcelRelationCursorController {

    private final OwnerParcelRelationCursorService service;

    public OwnerParcelRelationCursorController(OwnerParcelRelationCursorService service) {
        this.service = service;
    }

    @GetMapping("/cursor")
    @Operation(summary = "Search owner-parcel relations with keyset/cursor pagination")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public RelationCursorPage search(
        @RequestParam(defaultValue = "") String search,
        @RequestParam(required = false) Boolean active,
        @RequestParam(defaultValue = "") String source,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String cursor) {
        return service.search(search, active, source, size, cursor);
    }
}
