package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

class IntegrationJobServiceTest {
    private final UUID organizationId = UUID.randomUUID();
    private final IntegrationRunRepository repository = mock(IntegrationRunRepository.class);
    private final OrganizationContext organizationContext = mock(OrganizationContext.class);
    private final DistributedJobLock jobLock = mock(DistributedJobLock.class);
    private IntegrationJobHandler handler;
    private IntegrationJobService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        handler = mock(IntegrationJobHandler.class);
        when(handler.key()).thenReturn(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        when(handler.manualSupported()).thenReturn(true);
        when(handler.configured()).thenReturn(true);
        when(handler.enabled()).thenReturn(true);
        when(handler.displayName()).thenReturn("Katastriandmed");
        when(handler.provider()).thenReturn("Maa- ja Ruumiamet");
        when(handler.purpose()).thenReturn("Test");
        when(handler.scheduleDescription()).thenReturn("Iga tund");
        when(organizationContext.requiredOrganizationId()).thenReturn(organizationId);
        when(organizationContext.subject()).thenReturn("admin-subject");
        when(repository.markRunning(any())).thenReturn(true);
        doAnswer(invocation -> {
            Supplier<Object> work = invocation.getArgument(1);
            return new DistributedJobLock.LockedResult<>(true, work.get());
        }).when(jobLock).tryRun(anyString(), any(Supplier.class));
        service = new IntegrationJobService(List.of(handler), repository, organizationContext,
            jobLock, new SyncTaskExecutor(), new ObjectMapper());
    }

    @Test
    void manualRunIsQueuedAndCompletedThroughTheSharedRunner() {
        UUID runId = UUID.randomUUID();
        when(repository.create(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "MANUAL", "admin-subject", "{\"from\":\"2026-08-01\"}"))
            .thenReturn(run(runId, "QUEUED"));
        when(handler.run(organizationId, Map.of("from", "2026-08-01")))
            .thenReturn(IntegrationJobResult.success(Map.of("updated", 7), "Valmis"));

        var result = service.startManual(IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            Map.of("from", "2026-08-01"));

        assertThat(result.id()).isEqualTo(runId);
        verify(repository).expireStaleActive(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        verify(repository).markRunning(runId);
        verify(repository).complete(runId, "SUCCEEDED", "{\"updated\":7}", "Valmis", null);
    }


    @Test
    void pythonRoutedManualRunOnlyCreatesDurableQueueRow() {
        UUID runId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "pythonJobKeys", "CADASTRE_PUBLIC_DATA");
        when(repository.create(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "MANUAL", "admin-subject", "{}"))
            .thenReturn(run(runId, "QUEUED"));

        var result = service.startManual(IntegrationJobKey.CADASTRE_PUBLIC_DATA, Map.of());

        assertThat(result.id()).isEqualTo(runId);
        verify(repository).expireStaleActive(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        verify(repository, never()).markRunning(runId);
        verify(handler, never()).run(any(), any());
    }

    @Test
    void javaWorkerClaimSetExcludesPythonRoutedJobs() {
        ReflectionTestUtils.setField(service, "pythonJobKeys", "CADASTRE_PUBLIC_DATA");

        assertThat(service.javaClaimableJobKeys()).doesNotContain("CADASTRE_PUBLIC_DATA");
    }

    @Test
    void scheduledRunRecoversStaleRunBeforeSubmittingNextCycle() {
        UUID runId = UUID.randomUUID();
        when(handler.scheduledOrganizations()).thenReturn(List.of(organizationId));
        when(repository.create(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "SCHEDULED", "system-integration-scheduler", "{}"))
            .thenReturn(run(runId, "QUEUED"));
        when(handler.run(organizationId, Map.of()))
            .thenReturn(IntegrationJobResult.success(Map.of("updated", 1), "Valmis"));

        service.runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);

        verify(repository).expireStaleActive(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        verify(repository).markRunning(runId);
        verify(repository).complete(runId, "SUCCEEDED", "{\"updated\":1}", "Valmis", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveRunHoldingAdvisoryLockIsNeverExpiredOrReplaced() {
        when(handler.scheduledOrganizations()).thenReturn(List.of(organizationId));
        when(repository.active(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA))
            .thenReturn(run(UUID.randomUUID(), "RUNNING"));
        DistributedJobLock busyLock = mock(DistributedJobLock.class);
        doAnswer(invocation -> new DistributedJobLock.LockedResult<>(false, null))
            .when(busyLock).tryRun(anyString(), any(Supplier.class));
        IntegrationJobService busyService = new IntegrationJobService(List.of(handler), repository,
            organizationContext, busyLock, new SyncTaskExecutor(), new ObjectMapper());

        busyService.runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);

        verify(repository, never()).expireStaleActive(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        verify(repository, never()).create(any(), any(), anyString(), anyString(), anyString());
        verify(handler, never()).run(any(), any());
    }

    @Test
    void scheduledCallbacksOnlyEnqueueSoSlowFeedCannotBlockAnotherFeed() {
        IntegrationJobHandler inheritance = mock(IntegrationJobHandler.class);
        when(inheritance.key()).thenReturn(IntegrationJobKey.INHERITANCE_NOTICES);
        when(inheritance.configured()).thenReturn(true);
        when(inheritance.enabled()).thenReturn(true);
        when(inheritance.scheduledOrganizations()).thenReturn(List.of(organizationId));
        when(handler.scheduledOrganizations()).thenReturn(List.of(organizationId));

        UUID cadastreRun = UUID.randomUUID();
        UUID inheritanceRun = UUID.randomUUID();
        when(repository.create(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "SCHEDULED", "system-integration-scheduler", "{}"))
            .thenReturn(run(cadastreRun, IntegrationJobKey.CADASTRE_PUBLIC_DATA, "QUEUED"));
        when(repository.create(organizationId, IntegrationJobKey.INHERITANCE_NOTICES,
            "SCHEDULED", "system-integration-scheduler", "{}"))
            .thenReturn(run(inheritanceRun, IntegrationJobKey.INHERITANCE_NOTICES, "QUEUED"));

        List<Runnable> queuedTasks = new ArrayList<>();
        TaskExecutor queueOnlyExecutor = queuedTasks::add;
        IntegrationJobService asyncService = new IntegrationJobService(List.of(handler, inheritance), repository,
            organizationContext, jobLock, queueOnlyExecutor, new ObjectMapper());

        asyncService.runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        asyncService.runScheduledAll(IntegrationJobKey.INHERITANCE_NOTICES);

        assertThat(queuedTasks).hasSize(2);
        verify(handler, never()).run(any(), any());
        verify(inheritance, never()).run(any(), any());
    }

    @Test
    void scheduledExecutorSaturationMarksRunFailedInsteadOfLeavingItQueued() {
        UUID runId = UUID.randomUUID();
        when(handler.scheduledOrganizations()).thenReturn(List.of(organizationId));
        when(repository.create(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "SCHEDULED", "system-integration-scheduler", "{}"))
            .thenReturn(run(runId, "QUEUED"));
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("integration queue full");
        };
        IntegrationJobService saturatedService = new IntegrationJobService(List.of(handler), repository,
            organizationContext, jobLock, rejectingExecutor, new ObjectMapper());

        saturatedService.runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);

        verify(repository).complete(runId, "FAILED", "{}",
            "Ajastatud sünkroonimist ei õnnestunud tööjärjekorda lisada.", "integration queue full");
        verify(handler, never()).run(any(), any());
    }

    @Test
    void expiredQueuedTaskDoesNotCallProviderWhenItEventuallyWakesUp() {
        UUID runId = UUID.randomUUID();
        when(repository.create(any(), any(), anyString(), anyString(), anyString())).thenReturn(run(runId, "QUEUED"));
        when(repository.markRunning(runId)).thenReturn(false);

        service.startManual(IntegrationJobKey.CADASTRE_PUBLIC_DATA, Map.of());

        verify(repository).markRunning(runId);
        verify(handler, never()).run(any(), any());
    }

    @Test
    void failedRunStoresAConciseRedactedError() {
        UUID runId = UUID.randomUUID();
        when(repository.create(any(), any(), anyString(), anyString(), anyString())).thenReturn(run(runId, "QUEUED"));
        when(handler.run(any(), any())).thenThrow(new IllegalStateException("token=very-secret\nupstream failed"));

        service.startManual(IntegrationJobKey.CADASTRE_PUBLIC_DATA, Map.of());

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repository).complete(eq(runId), eq("FAILED"), eq("{}"),
            eq("Sünkroonimine ebaõnnestus."), error.capture());
        assertThat(error.getValue()).contains("token=[redacted]").doesNotContain("very-secret").doesNotContain("\n");
    }

    private static IntegrationJobService.RunView run(UUID id, String state) {
        return run(id, IntegrationJobKey.CADASTRE_PUBLIC_DATA, state);
    }

    private static IntegrationJobService.RunView run(UUID id, IntegrationJobKey key, String state) {
        return new IntegrationJobService.RunView(id, key.name(),
            "MANUAL", state, "admin-subject", "{}", "{}", null, null,
            Instant.now(), null, null);
    }
}
