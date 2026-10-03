package ee.forestiq.ownership;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

@Component
class WeaselTransitionIntegrationJob implements IntegrationJobHandler {
    private final WeaselProperties properties;
    private final OwnershipTransitionService service;

    WeaselTransitionIntegrationJob(WeaselProperties properties, OwnershipTransitionService service) {
        this.properties = properties;
        this.service = service;
    }

    public IntegrationJobKey key() { return IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS; }
    public String displayName() { return "Weaseli omandimuutused"; }
    public String provider() { return "Weasel"; }
    public String purpose() { return "Katastriüksuste omandimuutuste töösignaalid"; }
    public String scheduleDescription() {
        return "Iga " + Math.max(1, properties.getAutoRefreshFixedDelayMs() / 3_600_000L) + " tunni järel";
    }
    public boolean configured() { return properties.isConfigured(); }
    public boolean enabled() { return properties.isAutoRefreshEnabled(); }
    public List<UUID> scheduledOrganizations() {
        if (!configured()) return List.of();
        try { return List.of(UUID.fromString(properties.getOrganizationId())); }
        catch (IllegalArgumentException ignored) { return List.of(); }
    }
    public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
        var result = service.syncOrganization(organizationId, "SHARED_RUNNER");
        Map<String, Object> metrics = Map.of("pages", result.pages(), "matched", result.matched(),
            "imported", result.imported(), "failed", result.failed());
        return result.failed() > 0
            ? IntegrationJobResult.partial(metrics, result.message())
            : IntegrationJobResult.success(metrics, result.message());
    }
}
