package ee.forestiq.owner;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/owners")
@Tag(name = "Owners", description = "Organization-scoped forest owner CRM")
public class OwnerCursorController {

    private final OwnerCursorService ownerCursorService;

    public OwnerCursorController(OwnerCursorService ownerCursorService) {
        this.ownerCursorService = ownerCursorService;
    }

    @GetMapping("/cursor")
    @Operation(summary = "Search owners with keyset/cursor pagination")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public OwnerCursorPage search(
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String cursor) {
        return ownerCursorService.search(search, size, cursor);
    }
}
