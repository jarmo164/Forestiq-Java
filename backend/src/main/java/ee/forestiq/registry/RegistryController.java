package ee.forestiq.registry;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.registry.ExternalRegistryService.RegistryHealth;
import ee.forestiq.registry.RegistrySyncService.SyncResult;

@RestController
@RequestMapping("/api/v1/registry")
@Tag(name = "External registries", description = "Official Maa-amet, Metsaportaal and RIK data synchronisation")
public class RegistryController {

    private final RegistrySyncService syncService;
    private final RegistryFreshnessService freshnessService;

    public RegistryController(RegistrySyncService syncService, RegistryFreshnessService freshnessService) {
        this.syncService = syncService;
        this.freshnessService = freshnessService;
    }

    @GetMapping("/freshness")
    @Operation(summary = "Show official-data freshness SLA, stale parcels and recent provider failures")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RegistryFreshnessService.RegistryFreshnessDashboard freshness() {
        return freshnessService.dashboard();
    }

    @PostMapping("/freshness/recover")
    @Operation(summary = "Run a capped priority recovery batch for stale official data")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RegistryFreshnessService.RecoveryRun recoverStale(@RequestBody(required = false) RecoveryRequest request) {
        int batchSize = request == null || request.batchSize() == null ? 25 : request.batchSize();
        return freshnessService.recoverStale(batchSize, "MANUAL");
    }

    @GetMapping("/health")
    @Operation(summary = "Check configured official registry connections and required public WFS layers")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public RegistryHealth health() {
        return syncService.registryHealth();
    }

    @PostMapping("/cadastres/{cadastralCode}/refresh-all")
    @Operation(summary = "Refresh cadastral, forest-notification and forest-compartment data for one parcel")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public Map<String, SyncResult> refreshAll(@PathVariable String cadastralCode) {
        return syncService.refreshAll(cadastralCode);
    }

    @PostMapping("/cadastres/{cadastralCode}/maaamet/refresh")
    @Operation(summary = "Run a targeted Maa-amet WFS diagnostic without overwriting canonical cadastre data")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public SyncResult refreshMaaamet(@PathVariable String cadastralCode) {
        return syncService.refreshMaaamet(cadastralCode);
    }

    @PostMapping("/cadastres/{cadastralCode}/metsaregister/notifications/refresh")
    @Operation(summary = "Refresh a parcel's public forest notifications from Metsaportaal")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public SyncResult refreshForestNotifications(@PathVariable String cadastralCode) {
        return syncService.refreshForestNotifications(cadastralCode);
    }

    @PostMapping("/cadastres/{cadastralCode}/metsaregister/plan/refresh")
    @Operation(summary = "Refresh a parcel's public forest-plan / compartment information from Metsaportaal")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public SyncResult refreshForestPlan(@PathVariable String cadastralCode) {
        return syncService.refreshForestPlan(cadastralCode);
    }

    @PostMapping("/rik/cadastre-count")
    @Operation(summary = "Count land-register objects through the organisation's configured RIK service account")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public Map<String, Integer> rikCadastreCount(@RequestBody RikCountRequest request) {
        int count = syncService.fetchRikCadastreCount(request.personalOrRegistryCode(), request.firstName(), request.lastNameOrCompany());
        return Map.of("count", count);
    }

    public record RikCountRequest(String personalOrRegistryCode, String firstName, String lastNameOrCompany) { }
    public record RecoveryRequest(Integer batchSize) { }
}
