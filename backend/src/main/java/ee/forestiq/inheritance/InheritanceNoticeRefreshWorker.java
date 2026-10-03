package ee.forestiq.inheritance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobService;

/**
 * Controlled background matching of CRM people to the private official-notice
 * source. It is deliberately disabled by default because every lookup uses a
 * personal code and must be enabled only after the data-sharing agreement and
 * source credentials are configured for the organisation.
 */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'scheduler' || '${forestiq.runtime.role:all}' == 'all'")
@ConditionalOnProperty(prefix = "forestiq.inheritance-source", name = "auto-refresh-enabled", havingValue = "true")
public class InheritanceNoticeRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(InheritanceNoticeRefreshWorker.class);
    private final IntegrationJobService integrationJobs;

    public InheritanceNoticeRefreshWorker(IntegrationJobService integrationJobs) {
        this.integrationJobs = integrationJobs;
    }

    @Scheduled(initialDelay = 180_000L, fixedDelayString = "${forestiq.inheritance-source.auto-refresh-fixed-delay-ms:1800000}")
    public void refreshOfficialNotices() {
        integrationJobs.runScheduledAll(IntegrationJobKey.INHERITANCE_NOTICES);
        log.info("Inheritance notice jobs submitted through the durable runner");
    }
}
