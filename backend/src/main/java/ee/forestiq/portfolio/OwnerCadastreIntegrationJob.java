package ee.forestiq.portfolio;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

@Component
class OwnerCadastreIntegrationJob implements IntegrationJobHandler {
    private final MetsisPortfolioProperties properties;
    private final MetsisPortfolioSyncService service;

    OwnerCadastreIntegrationJob(MetsisPortfolioProperties properties, MetsisPortfolioSyncService service) {
        this.properties = properties;
        this.service = service;
    }

    public IntegrationJobKey key() { return IntegrationJobKey.OWNER_CADASTRE_RELATIONS; }
    public String displayName() { return "Omaniku–katastri seosed"; }
    public String provider() { return "MetsIS / Forestek"; }
    public String purpose() { return "Täielik omanike ja aktiivsete katastriseoste snapshot"; }
    public String scheduleDescription() {
        return "Iga " + Math.max(1, properties.getAutoSyncFixedDelayMs() / 3_600_000L) + " tunni järel";
    }
    public boolean configured() { return properties.isConfigured(); }
    public boolean enabled() { return properties.isAutoSyncEnabled(); }
    public List<UUID> scheduledOrganizations() {
        if (!configured()) return List.of();
        try { return List.of(service.configuredOrganizationId()); }
        catch (RuntimeException ignored) { return List.of(); }
    }
    public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
        var result = service.syncOrganization(organizationId, "SHARED_RUNNER");
        Map<String, Object> metrics = Map.of("pages", result.pages(), "rows", result.rows(),
            "relationsAdded", result.relationsAdded(), "relationsClosed", result.relationsClosed());
        return IntegrationJobResult.success(metrics, result.message());
    }
}
