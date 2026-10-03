package ee.forestiq.portfolio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobService;

/** Bounded daily portfolio projection; disabled until a valid data-sharing configuration is in place. */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'scheduler' || '${forestiq.runtime.role:all}' == 'all'")
public class MetsisPortfolioAutoSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(MetsisPortfolioAutoSyncWorker.class);

    private final MetsisPortfolioProperties properties;
    private final IntegrationJobService integrationJobs;

    public MetsisPortfolioAutoSyncWorker(MetsisPortfolioProperties properties, IntegrationJobService integrationJobs) {
        this.properties = properties;
        this.integrationJobs = integrationJobs;
    }

    @Scheduled(fixedDelayString = "${forestiq.metsis-portfolio.auto-sync-fixed-delay-ms:86400000}")
    public void refreshConfiguredPortfolio() {
        if (!properties.isAutoSyncEnabled() || !properties.isConfigured()) return;
        integrationJobs.runScheduledAll(IntegrationJobKey.OWNER_CADASTRE_RELATIONS);
        log.info("Owner/cadastre snapshot submitted through the durable runner");
    }
}
