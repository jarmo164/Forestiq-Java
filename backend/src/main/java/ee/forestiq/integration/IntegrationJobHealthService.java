package ee.forestiq.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class IntegrationJobHealthService {
    private static final Logger log = LoggerFactory.getLogger(IntegrationJobHealthService.class);
    private static final Map<IntegrationJobKey, Duration> FRESHNESS_SLOS = Map.of(
        IntegrationJobKey.CADASTRE_PUBLIC_DATA, Duration.ofHours(168),
        IntegrationJobKey.FOREST_NOTIFICATIONS, Duration.ofHours(24),
        IntegrationJobKey.FOREST_PLAN, Duration.ofHours(24),
        IntegrationJobKey.OWNER_CADASTRE_RELATIONS, Duration.ofHours(36),
        IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS, Duration.ofHours(12),
        IntegrationJobKey.INHERITANCE_NOTICES, Duration.ofHours(2)
    );

    private final IntegrationRunRepository runs;
    private final IntegrationRunParameterCodec parameterCodec;

    IntegrationJobHealthService(IntegrationRunRepository runs, IntegrationRunParameterCodec parameterCodec) {
        this.runs = runs;
        this.parameterCodec = parameterCodec;
    }

    IntegrationJobService.HealthView health(UUID orgId, IntegrationJobKey key, String configurationState,
                                            IntegrationJobService.RunView latest,
                                            IntegrationJobService.RunView latestSuccess,
                                            IntegrationJobService.RunView current) {
        Duration slo = freshnessSlo(key);
        long sloSeconds = slo.toSeconds();
        Long successAge = ageSeconds(latestSuccess == null ? null : latestSuccess.finishedAt());
        Long currentAge = current == null ? null : ageSeconds(
            current.startedAt() == null ? current.queuedAt() : current.startedAt());

        if ("MISCONFIGURED".equals(configurationState)) {
            return new IntegrationJobService.HealthView("MISCONFIGURED", sloSeconds, successAge, currentAge, 0,
                false, null, "Andmeallika tootmisseadistus on puudulik.");
        }
        if ("DISABLED".equals(configurationState)) {
            return new IntegrationJobService.HealthView("DISABLED", sloSeconds, successAge, currentAge, 0,
                false, null, "Automaatne sünkroonimine on välja lülitatud.");
        }

        boolean metricBacklog = parameterCodec.metricTrue(latest == null ? null : latest.metrics(),
            "backlogRemaining", "catchupRemaining");
        int failureStreak;
        boolean backlog;
        Long cursorLag;
        try {
            failureStreak = runs.failureStreak(orgId, key);
            backlog = runs.durableBacklog(orgId, key) || metricBacklog;
            cursorLag = runs.cursorLagSeconds(orgId, key);
        } catch (RuntimeException exception) {
            log.warn("Integration health probe failed for organization {} job {}: {}",
                orgId, key, IntegrationJobService.safeError(exception));
            return new IntegrationJobService.HealthView("DEGRADED", sloSeconds, successAge, currentAge, 0,
                metricBacklog, null,
                "Andmevoo tervisemõõdikute lugemine ebaõnnestus; jooksude ajalugu on endiselt saadaval.");
        }

        if (latestSuccess == null || latestSuccess.finishedAt() == null) {
            return new IntegrationJobService.HealthView("STALE", sloSeconds, successAge, currentAge, failureStreak,
                backlog, cursorLag, "Andmevoog ei ole veel ühtegi kinnitatud edukat jooksu lõpetanud.");
        }
        if (successAge != null && successAge > sloSeconds) {
            return new IntegrationJobService.HealthView("STALE", sloSeconds, successAge, currentAge, failureStreak,
                backlog, cursorLag, "Viimane edukas sünkroonimine ületab värskuse SLO-d.");
        }
        if (cursorLag != null && cursorLag > sloSeconds) {
            return new IntegrationJobService.HealthView("STALE", sloSeconds, successAge, currentAge, failureStreak,
                backlog, cursorLag, "Andmevoo cursor on värskuse SLO-st rohkem maha jäänud.");
        }
        if (currentAge != null && currentAge > sloSeconds) {
            return new IntegrationJobService.HealthView("DEGRADED", sloSeconds, successAge, currentAge, failureStreak,
                backlog, cursorLag, "Praegune sünkroonimine kestab kauem kui värskuse SLO.");
        }
        if (failureStreak > 0) {
            return new IntegrationJobService.HealthView("DEGRADED", sloSeconds, successAge, currentAge, failureStreak,
                backlog, cursorLag, "Pärast viimast õnnestumist on ebaõnnestunud või osalisi jookse.");
        }
        if (backlog) {
            return new IntegrationJobService.HealthView("DEGRADED", sloSeconds, successAge, currentAge, failureStreak,
                true, cursorLag, "Andmevoog töötab, kuid catch-up või provider backlog ei ole veel tühjenenud.");
        }
        return new IntegrationJobService.HealthView("HEALTHY", sloSeconds, successAge, currentAge, 0,
            false, cursorLag, "Andmevoog on SLO piires värske.");
    }

    static Duration freshnessSlo(IntegrationJobKey key) {
        return FRESHNESS_SLOS.getOrDefault(key, Duration.ofHours(24));
    }

    private static Long ageSeconds(Instant instant) {
        return instant == null ? null : Math.max(0L, Duration.between(instant, Instant.now()).toSeconds());
    }
}
