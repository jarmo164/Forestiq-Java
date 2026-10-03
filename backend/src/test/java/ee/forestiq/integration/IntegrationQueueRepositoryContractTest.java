package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Set;

import org.mockito.Mockito;

class IntegrationQueueRepositoryContractTest {

    @Test
    void claimUsesPostgresSkipLockedAndLeaseFields() {
        DSLContext dsl = Mockito.mock(DSLContext.class);
        IntegrationQueueRepository repository = new IntegrationQueueRepository(dsl);

        assertThat(repository.claimNext(Set.of("CADASTRE_PUBLIC_DATA"))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(dsl).fetchOne(sql.capture(), any(Object[].class));

        assertThat(sql.getValue())
            .contains("FOR UPDATE SKIP LOCKED")
            .contains("heartbeat_at")
            .contains("worker_id")
            .contains("lease_expires_at")
            .contains("attempts = run.attempts + 1")
            .contains("job_key = ANY(?::text[])");
    }
}
