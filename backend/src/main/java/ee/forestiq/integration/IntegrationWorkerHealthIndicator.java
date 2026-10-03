package ee.forestiq.integration;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Worker-specific health component exposed by Spring Boot Actuator as
 * /actuator/health/integrationWorker while the runtime role is `worker`.
 */
@Component("integrationWorkerHealthIndicator")
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'worker'")
class IntegrationWorkerHealthIndicator implements HealthIndicator {
    private final IntegrationQueueRepository queue;
    private final IntegrationQueueWorker worker;
    private final long stalePollThresholdMillis;

    IntegrationWorkerHealthIndicator(IntegrationQueueRepository queue, IntegrationQueueWorker worker,
                                     @Value("${forestiq.runtime.worker-poll-ms:2000}") long pollMillis) {
        this.queue = queue;
        this.worker = worker;
        long safePollMillis = Math.max(250L, pollMillis);
        this.stalePollThresholdMillis = Math.max(Duration.ofSeconds(15).toMillis(), safePollMillis * 5L);
    }

    @Override
    public Health health() {
        try {
            long queued = queue.queuedCount();
            long running = queue.runningCount();
            int active = worker.activeCount();
            int max = worker.concurrency();
            long lastPoll = worker.lastPollEpochMillis();
            long lastPollAgeMillis = lastPoll == 0L ? -1L : Math.max(0L, Instant.now().toEpochMilli() - lastPoll);
            boolean saturated = max > 0 && active >= max && queued > 0;
            boolean stalePoll = worker.isAcceptingWork() && lastPollAgeMillis >= 0L
                && lastPollAgeMillis > stalePollThresholdMillis;

            Health.Builder builder;
            if (!worker.isAcceptingWork()) {
                builder = Health.outOfService().withDetail("reason", "worker-shutting-down");
            } else if (stalePoll) {
                builder = Health.down().withDetail("reason", "worker-poll-stale");
            } else if (saturated) {
                builder = Health.status("DEGRADED");
            } else {
                builder = Health.up();
            }

            return builder
                .withDetail("acceptingWork", worker.isAcceptingWork())
                .withDetail("queued", queued)
                .withDetail("running", running)
                .withDetail("activeLocal", active)
                .withDetail("maxConcurrency", max)
                .withDetail("saturated", saturated)
                .withDetail("lastPollAgeMillis", lastPollAgeMillis)
                .build();
        } catch (RuntimeException exception) {
            return Health.down(exception)
                .withDetail("acceptingWork", worker.isAcceptingWork())
                .withDetail("activeLocal", worker.activeCount())
                .withDetail("maxConcurrency", worker.concurrency())
                .build();
        }
    }
}
