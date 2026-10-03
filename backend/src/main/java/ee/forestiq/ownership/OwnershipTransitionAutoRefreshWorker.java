package ee.forestiq.ownership;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobService;

/** Periodically imports bounded Weasel ownership-transition windows when explicitly enabled. */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'scheduler' || '${forestiq.runtime.role:all}' == 'all'")
public class OwnershipTransitionAutoRefreshWorker {
    private static final Logger log = LoggerFactory.getLogger(OwnershipTransitionAutoRefreshWorker.class);
    private final WeaselProperties properties;
    private final IntegrationJobService integrationJobs;

    public OwnershipTransitionAutoRefreshWorker(WeaselProperties properties, IntegrationJobService integrationJobs) {
        this.properties = properties;
        this.integrationJobs = integrationJobs;
    }

    @Scheduled(initialDelay = 180_000L, fixedDelayString = "${forestiq.weasel.auto-refresh-fixed-delay-ms:21600000}")
    public void refreshOwnershipTransitions() {
        if (!properties.isAutoRefreshEnabled() || !properties.isConfigured()) return;
        integrationJobs.runScheduledAll(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS);
        log.info("Weasel ownership-transition job submitted through the durable runner");
    }
}
