package ee.forestiq.integration;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Dedicated runtime consumer for durable integration runs.
 *
 * The `worker` role never creates schedules: it only atomically claims already
 * queued work and executes it through the same idempotent/advisory-lock path.
 * PostgreSQL remains the only durable queue; the local executor has no backlog.
 */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'worker'")
class IntegrationQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(IntegrationQueueWorker.class);

    private final IntegrationQueueRepository queue;
    private final IntegrationJobService jobs;
    private final ThreadPoolTaskExecutor executor;
    private final int batchSize;
    private final int concurrency;
    private final AtomicBoolean acceptingWork = new AtomicBoolean(true);
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong durableRunning = new AtomicLong();
    private final AtomicLong lastPollEpochMillis = new AtomicLong();
    private final Counter claimed;
    private final Counter rejected;
    private final Counter claimReleaseFailures;

    IntegrationQueueWorker(IntegrationQueueRepository queue, IntegrationJobService jobs,
                           @Qualifier("integrationWorkerTaskExecutor") ThreadPoolTaskExecutor executor,
                           MeterRegistry meterRegistry,
                           @Value("${forestiq.runtime.worker-batch-size:8}") int batchSize,
                           @Value("${forestiq.runtime.worker-concurrency:4}") int configuredConcurrency) {
        this.queue = queue;
        this.jobs = jobs;
        this.executor = executor;
        this.batchSize = Math.max(1, Math.min(batchSize, 64));
        this.concurrency = Math.max(1, Math.min(configuredConcurrency, 32));
        this.claimed = Counter.builder("forestiq.integration.worker.claimed")
            .description("Durable integration runs claimed by this worker runtime")
            .register(meterRegistry);
        this.rejected = Counter.builder("forestiq.integration.worker.rejected")
            .description("Claimed runs rejected by the bounded local worker executor")
            .register(meterRegistry);
        this.claimReleaseFailures = Counter.builder("forestiq.integration.worker.claim_release_failures")
            .description("Claimed runs that could not be safely returned to PostgreSQL after local rejection")
            .register(meterRegistry);
        Gauge.builder("forestiq.integration.queue.depth", queueDepth, AtomicLong::get)
            .description("Integration runs currently waiting in PostgreSQL")
            .register(meterRegistry);
        Gauge.builder("forestiq.integration.queue.running", durableRunning, AtomicLong::get)
            .description("Integration runs currently marked RUNNING in PostgreSQL")
            .register(meterRegistry);
        Gauge.builder("forestiq.integration.worker.active", active, AtomicInteger::get)
            .description("Integration jobs actively owned by this worker process")
            .register(meterRegistry);
        Gauge.builder("forestiq.integration.worker.saturation", active,
                value -> Math.min(1.0d, value.doubleValue() / this.concurrency))
            .description("Local worker saturation ratio from 0 to 1")
            .register(meterRegistry);
        Gauge.builder("forestiq.integration.worker.accepting", acceptingWork,
                value -> value.get() ? 1.0d : 0.0d)
            .description("1 while this worker is allowed to claim new durable runs")
            .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${forestiq.runtime.worker-initial-delay-ms:5000}",
        fixedDelayString = "${forestiq.runtime.worker-poll-ms:2000}")
    void drainQueue() {
        if (!acceptingWork.get()) return;
        refreshDurableMetrics();
        int available = Math.max(0, concurrency - active.get());
        int claimsThisPoll = Math.min(batchSize, available);
        for (int index = 0; index < claimsThisPoll && acceptingWork.get(); index++) {
            var javaJobKeys = jobs.javaClaimableJobKeys();
            var next = javaJobKeys == null ? queue.claimNext() : queue.claimNext(javaJobKeys);
            if (next.isEmpty()) break;
            var run = next.orElseThrow();
            claimed.increment();
            active.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        jobs.executeClaimed(run);
                    } finally {
                        active.decrementAndGet();
                    }
                });
            } catch (RuntimeException exception) {
                active.decrementAndGet();
                rejected.increment();
                if (!queue.releaseClaim(run.id())) {
                    claimReleaseFailures.increment();
                    log.error("Worker executor rejected run {}, but its durable claim could not be released", run.id(), exception);
                } else {
                    log.warn("Worker executor rejected run {}; returned claim to PostgreSQL", run.id());
                }
                break;
            }
        }
        refreshDurableMetrics();
        lastPollEpochMillis.set(Instant.now().toEpochMilli());
    }

    @PreDestroy
    void stopAcceptingWork() {
        acceptingWork.set(false);
        log.info("Integration worker is stopping; no new durable runs will be claimed. active={}", active.get());
    }

    boolean isAcceptingWork() {
        return acceptingWork.get();
    }

    int activeCount() {
        return active.get();
    }

    int concurrency() {
        return concurrency;
    }

    long lastPollEpochMillis() {
        return lastPollEpochMillis.get();
    }

    private void refreshDurableMetrics() {
        queueDepth.set(queue.queuedCount());
        durableRunning.set(queue.runningCount());
    }
}
