package ee.forestiq.inheritance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

class InheritanceNoticeIntegrationJobTest {

    @Test
    void firstScheduledRunChecksYesterdayAndTodayAndPersistsCursorMetric() {
        DSLContext dsl = mock(DSLContext.class);
        InheritanceSourceProperties properties = properties();
        InheritanceNoticeIngestionService service = mock(InheritanceNoticeIngestionService.class);
        when(service.synchronize(any(), any())).thenReturn(
            new InheritanceNoticeIngestionService.SyncSummary(2, 0, 0, 0, 0, 0));
        InheritanceNoticeIntegrationJob job = new InheritanceNoticeIntegrationJob(dsl, properties, service);
        UUID organizationId = UUID.randomUUID();

        var result = job.run(organizationId, Map.of());

        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalDate>> days = ArgumentCaptor.forClass(List.class);
        verify(service).synchronize(eq(organizationId), days.capture());
        assertThat(days.getValue()).containsExactly(today.minusDays(1), today);
        assertThat(result.metrics()).containsEntry("lastCompletedDate", today.minusDays(1).toString());
        assertThat(result.metrics()).containsEntry("catchupRemaining", false);
    }

    @Test
    void multiDayDowntimeCatchesUpOldestDaysWithinCapAndKeepsTodayYesterdayOverlap() {
        DSLContext dsl = mock(DSLContext.class);
        InheritanceSourceProperties properties = properties();
        properties.setMaxBackfillDays(4);
        InheritanceNoticeIngestionService service = mock(InheritanceNoticeIngestionService.class);
        UUID organizationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        LocalDate previousCursor = today.minusDays(7);
        when(dsl.fetchValue(anyString(), eq(organizationId), eq(IntegrationJobKey.INHERITANCE_NOTICES.name())))
            .thenReturn(previousCursor.toString());
        when(service.synchronize(any(), any())).thenReturn(
            new InheritanceNoticeIngestionService.SyncSummary(5, 0, 0, 0, 0, 0));
        InheritanceNoticeIntegrationJob job = new InheritanceNoticeIntegrationJob(dsl, properties, service);

        var result = job.run(organizationId, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalDate>> days = ArgumentCaptor.forClass(List.class);
        verify(service).synchronize(eq(organizationId), days.capture());
        assertThat(days.getValue()).containsExactly(
            today.minusDays(6), today.minusDays(5), today.minusDays(4),
            today.minusDays(1), today);
        assertThat(result.outcome()).isEqualTo(IntegrationJobResult.Outcome.SUCCEEDED);
        assertThat(result.metrics()).containsEntry("lastCompletedDate", today.minusDays(4).toString());
        assertThat(result.metrics()).containsEntry("catchupRemaining", true);
    }

    @Test
    void failedScheduledBatchDoesNotAdvanceHistoricalCursor() {
        DSLContext dsl = mock(DSLContext.class);
        InheritanceSourceProperties properties = properties();
        InheritanceNoticeIngestionService service = mock(InheritanceNoticeIngestionService.class);
        UUID organizationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        LocalDate previousCursor = today.minusDays(4);
        when(dsl.fetchValue(anyString(), eq(organizationId), eq(IntegrationJobKey.INHERITANCE_NOTICES.name())))
            .thenReturn(previousCursor.toString());
        when(service.synchronize(any(), any())).thenReturn(
            new InheritanceNoticeIngestionService.SyncSummary(4, 3, 1, 1, 0, 1));
        InheritanceNoticeIntegrationJob job = new InheritanceNoticeIntegrationJob(dsl, properties, service);

        var result = job.run(organizationId, Map.of());

        assertThat(result.outcome()).isEqualTo(IntegrationJobResult.Outcome.PARTIAL);
        assertThat(result.metrics()).containsEntry("lastCompletedDate", previousCursor.toString());
    }

    @Test
    void yesterdayIsRecheckedEvenAfterHistoricalCursorHasReachedIt() {
        DSLContext dsl = mock(DSLContext.class);
        InheritanceSourceProperties properties = properties();
        InheritanceNoticeIngestionService service = mock(InheritanceNoticeIngestionService.class);
        UUID organizationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        LocalDate yesterday = today.minusDays(1);
        when(dsl.fetchValue(anyString(), eq(organizationId), eq(IntegrationJobKey.INHERITANCE_NOTICES.name())))
            .thenReturn(yesterday.toString());
        when(service.synchronize(any(), any())).thenReturn(
            new InheritanceNoticeIngestionService.SyncSummary(2, 0, 0, 0, 0, 0));
        InheritanceNoticeIntegrationJob job = new InheritanceNoticeIntegrationJob(dsl, properties, service);

        job.run(organizationId, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalDate>> days = ArgumentCaptor.forClass(List.class);
        verify(service).synchronize(eq(organizationId), days.capture());
        assertThat(days.getValue()).containsExactly(yesterday, today);
    }

    @Test
    void manualBackfillDoesNotReadOrPublishScheduledCursor() {
        DSLContext dsl = mock(DSLContext.class);
        InheritanceSourceProperties properties = properties();
        InheritanceNoticeIngestionService service = mock(InheritanceNoticeIngestionService.class);
        UUID organizationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        LocalDate from = today.minusDays(5);
        LocalDate to = today.minusDays(3);
        when(service.synchronize(any(), any())).thenReturn(
            new InheritanceNoticeIngestionService.SyncSummary(3, 0, 0, 0, 0, 0));
        InheritanceNoticeIntegrationJob job = new InheritanceNoticeIntegrationJob(dsl, properties, service);

        var result = job.run(organizationId, Map.of("from", from.toString(), "to", to.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalDate>> days = ArgumentCaptor.forClass(List.class);
        verify(service).synchronize(eq(organizationId), days.capture());
        assertThat(days.getValue()).containsExactly(from, from.plusDays(1), to);
        assertThat(result.metrics()).doesNotContainKeys("lastCompletedDate", "catchupRemaining");
    }

    private static InheritanceSourceProperties properties() {
        InheritanceSourceProperties properties = new InheritanceSourceProperties();
        properties.setUriTemplate("https://example.test/%d/%02d/%02d.xml");
        properties.setAutoRefreshEnabled(true);
        return properties;
    }
}
