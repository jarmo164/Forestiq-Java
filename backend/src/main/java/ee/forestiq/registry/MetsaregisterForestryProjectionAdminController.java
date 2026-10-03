package ee.forestiq.registry;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative evidence, publication and rollback surface for the canonical forestry generation. */
@RestController
@RequestMapping("/api/v1/admin/integrations/metsaregister-forestry/canonical")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class MetsaregisterForestryProjectionAdminController {
    private final MetsaregisterForestryProjectionService projection;

    public MetsaregisterForestryProjectionAdminController(MetsaregisterForestryProjectionService projection) {
        this.projection = projection;
    }

    @GetMapping
    public MetsaregisterForestryProjectionService.ProjectionStatus status() {
        return projection.status();
    }

    @PostMapping("/refresh")
    public MetsaregisterForestryProjectionService.ProjectionResult refresh() {
        return projection.refresh("MANUAL", actor());
    }

    @PostMapping("/rollback/{generationId}")
    public MetsaregisterForestryProjectionService.ProjectionStatus rollback(
        @PathVariable UUID generationId,
        @RequestBody(required = false) RollbackRequest request
    ) {
        String reason = request == null ? null : request.reason();
        return projection.rollback(generationId, actor(), reason);
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "admin-api" : authentication.getName();
    }

    public record RollbackRequest(String reason) { }
}
