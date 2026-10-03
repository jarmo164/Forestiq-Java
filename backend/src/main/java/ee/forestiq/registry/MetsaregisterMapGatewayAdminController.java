package ee.forestiq.registry;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations/metsaregister-map")
@PreAuthorize("hasRole('ORG_ADMIN')")
class MetsaregisterMapGatewayAdminController {
    private final MetsaregisterMapGatewayService gateway;

    MetsaregisterMapGatewayAdminController(MetsaregisterMapGatewayService gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/cache")
    Map<String, Long> cacheStatus() {
        return Map.of(
            "estimatedEntries", gateway.estimatedCacheSize(),
            "estimatedWeightBytes", gateway.estimatedCacheWeightBytes());
    }

    @DeleteMapping("/cache")
    Map<String, Object> clearCache() {
        long beforeEntries = gateway.estimatedCacheSize();
        long beforeWeight = gateway.estimatedCacheWeightBytes();
        gateway.clearCache();
        return Map.of(
            "cleared", true,
            "estimatedEntriesBefore", beforeEntries,
            "estimatedWeightBytesBefore", beforeWeight);
    }
}
