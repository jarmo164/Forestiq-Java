package ee.forestiq.registry;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations/metsaregister-wfs")
@PreAuthorize("hasRole('ORG_ADMIN')")
class MetsaregisterWfsVerificationController {
    private final MetsaregisterWfsVerificationService verification;
    private final MetsaregisterWfsGenerationMaintenanceService maintenance;

    MetsaregisterWfsVerificationController(
        MetsaregisterWfsVerificationService verification,
        MetsaregisterWfsGenerationMaintenanceService maintenance
    ) {
        this.verification = verification;
        this.maintenance = maintenance;
    }

    @GetMapping("/verification")
    MetsaregisterWfsVerificationService.VerificationReport verify(
        @RequestParam(defaultValue = "false") boolean deep
    ) {
        return verification.verify(deep);
    }

    @PostMapping("/maintenance/cleanup-retired")
    MetsaregisterWfsGenerationMaintenanceService.CleanupResult cleanupRetired() {
        return maintenance.cleanupRetired();
    }
}
