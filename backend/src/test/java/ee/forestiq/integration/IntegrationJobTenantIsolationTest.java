package ee.forestiq.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

class IntegrationJobTenantIsolationTest {

    @Test
    @SuppressWarnings("unchecked")
    void claimedWorkerRunUsesDurableOrganizationInsteadOfAmbientRequestContext() {
        UUID ambientOrganization = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID durableOrganization = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID runId = UUID.randomUUID();

        IntegrationJobHandler handler = mock(IntegrationJobHandler.class);
        when(handler.key()).thenReturn(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        when(handler.run(durableOrganization, Map.of("cursor", "42")))
            .thenReturn(IntegrationJobResult.success(Map.of("updated", 1), "done"));

        IntegrationRunRepository runs = mock(IntegrationRunRepository.class);
        OrganizationContext organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ambientOrganization);
        DistributedJobLock jobLock = mock(DistributedJobLock.class);
        doAnswer(invocation -> {
            Supplier<Object> work = invocation.getArgument(1);
            return new DistributedJobLock.LockedResult<>(true, work.get());
        }).when(jobLock).tryRun(anyString(), any(Supplier.class));

        IntegrationJobService service = new IntegrationJobService(List.of(handler), runs, organizationContext,
            jobLock, new SyncTaskExecutor(), new ObjectMapper());

        service.executeClaimed(new IntegrationQueueRepository.ClaimedRun(
            runId, durableOrganization, IntegrationJobKey.CADASTRE_PUBLIC_DATA.name(), "{\"cursor\":\"42\"}"));

        verify(handler).run(durableOrganization, Map.of("cursor", "42"));
        verify(handler, never()).run(ambientOrganization, Map.of("cursor", "42"));
        verify(runs).complete(runId, "SUCCEEDED", "{\"updated\":1}", "done", null);
    }
}
