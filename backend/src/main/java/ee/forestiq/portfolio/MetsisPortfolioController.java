package ee.forestiq.portfolio;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only operational endpoints for the canonical MetsIS portfolio projection. */
@RestController
@RequestMapping("/api/v1/metsis-portfolio")
public class MetsisPortfolioController {
    private final MetsisPortfolioSyncService syncService;

    public MetsisPortfolioController(MetsisPortfolioSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public MetsisPortfolioSyncService.SyncStatus status() {
        return syncService.statusForCurrentOrganization();
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public MetsisPortfolioSyncService.SyncResult sync() {
        try { return syncService.syncCurrentOrganization(); }
        catch (ResponseStatusException exception) { throw exception; }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MetsIS portfelli sünkroniseerimine ebaõnnestus", exception); }
    }
}
