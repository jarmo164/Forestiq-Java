package ee.forestiq.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobService;

/**
 * Periodically refreshes only missing or stale public-data snapshots. It is
 * disabled by default so deployments explicitly opt in after reviewing source
 * terms and capacity; interactive refresh remains available regardless.
 */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'scheduler' || '${forestiq.runtime.role:all}' == 'all'")
public class RegistryAutoRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(RegistryAutoRefreshWorker.class);

    private final RegistryProperties properties;
    private final IntegrationJobService integrationJobs;

    public RegistryAutoRefreshWorker(RegistryProperties properties, IntegrationJobService integrationJobs) {
        this.properties = properties;
        this.integrationJobs = integrationJobs;
    }

    @Scheduled(initialDelay = 120_000L, fixedDelayString = "${forestiq.registry.auto-refresh.fixed-delay-ms:21600000}")
    public void refreshStalePublicData() {
        RegistryProperties.AutoRefresh config = properties.getAutoRefresh();
        if (!config.isEnabled()) return;
        integrationJobs.runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        integrationJobs.runScheduledAll(IntegrationJobKey.METSAREGISTER_WFS_MIRROR);
        integrationJobs.runScheduledAll(IntegrationJobKey.FOREST_NOTIFICATIONS);
        integrationJobs.runScheduledAll(IntegrationJobKey.FOREST_PLAN);
        log.info("Registry integration jobs submitted through the durable runner");
    }
}
