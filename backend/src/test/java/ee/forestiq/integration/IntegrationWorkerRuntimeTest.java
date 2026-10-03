package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class IntegrationWorkerRuntimeTest {

    @Test
    void shutdownStopsNewClaimsBeforeExecutorIsDestroyed() {
        IntegrationQueueRepository queue = mock(IntegrationQueueRepository.class);
        IntegrationJobService jobs = mock(IntegrationJobService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        IntegrationQueueWorker worker = new IntegrationQueueWorker(
            queue, jobs, executor, new SimpleMeterRegistry(), 8, 4);

        worker.stopAcceptingWork();
        worker.drainQueue();

        assertThat(worker.isAcceptingWork()).isFalse();
        verify(queue, never()).claimNext();
        verify(queue, never()).queuedCount();
    }

    @Test
    void workerHealthReportsQueueActivityAndSaturation() {
        IntegrationQueueRepository queue = mock(IntegrationQueueRepository.class);
        IntegrationQueueWorker worker = mock(IntegrationQueueWorker.class);
        when(queue.queuedCount()).thenReturn(12L);
        when(queue.runningCount()).thenReturn(4L);
        when(worker.isAcceptingWork()).thenReturn(true);
        when(worker.activeCount()).thenReturn(4);
        when(worker.concurrency()).thenReturn(4);
        when(worker.lastPollEpochMillis()).thenReturn(System.currentTimeMillis());

        var health = new IntegrationWorkerHealthIndicator(queue, worker, 2_000L).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(health.getDetails())
            .containsEntry("queued", 12L)
            .containsEntry("running", 4L)
            .containsEntry("activeLocal", 4)
            .containsEntry("maxConcurrency", 4)
            .containsEntry("saturated", true)
            .containsEntry("acceptingWork", true);
    }

    @Test
    void workerHealthBecomesOutOfServiceDuringGracefulShutdown() {
        IntegrationQueueRepository queue = mock(IntegrationQueueRepository.class);
        IntegrationQueueWorker worker = mock(IntegrationQueueWorker.class);
        when(queue.queuedCount()).thenReturn(2L);
        when(queue.runningCount()).thenReturn(1L);
        when(worker.isAcceptingWork()).thenReturn(false);
        when(worker.activeCount()).thenReturn(1);
        when(worker.concurrency()).thenReturn(4);

        var health = new IntegrationWorkerHealthIndicator(queue, worker, 2_000L).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("reason", "worker-shutting-down");
    }
}
