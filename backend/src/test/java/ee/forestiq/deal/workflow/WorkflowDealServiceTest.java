package ee.forestiq.deal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.workflow.WorkflowDealPort.HistoryQuery;
import ee.forestiq.deal.workflow.WorkflowDealPort.TimelineEvent;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class WorkflowDealServiceTest {
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FIRST_EVENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private DSLContext dsl;
    @Mock private OrganizationContext organizationContext;

    private WorkflowDealService service;

    @BeforeEach
    void setUp() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        service = new WorkflowDealService(dsl, organizationContext);
    }

    @Test
    void requireDealReturnsOrganizationScopedReference() {
        Record row = mock(Record.class);
        when(row.get("owner_id", UUID.class)).thenReturn(OWNER_ID);
        when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);

        var reference = service.requireDeal(DEAL_ID);

        assertThat(reference.dealId()).isEqualTo(DEAL_ID);
        assertThat(reference.ownerId()).isEqualTo(OWNER_ID);
    }

    @Test
    void requireDealRejectsMissingOrganizationScopedDeal() {
        when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(null);

        assertThatThrownBy(() -> service.requireDeal(DEAL_ID))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Tehingut ei leitud");
    }

    @Test
    void appendEventPersistsAuditDataWithCurrentSubject() {
        when(organizationContext.subject()).thenReturn("sales-subject");

        service.appendEvent(DEAL_ID, new TimelineEvent(
            "CONTACT_LOGGED", "Helistati", "USER", "PHONE", "CONTACTED", "Reached", 7L));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(dsl).execute(anyString(), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(
            ORGANIZATION_ID, DEAL_ID, "CONTACT_LOGGED", "Helistati", "USER", "PHONE", "CONTACTED", "Reached", "7", "sales-subject");
    }

    @Test
    void historyFiltersCursorPaginatesAndMapsCanonicalItems() {
        @SuppressWarnings("unchecked")
        Result<Record> result = mock(Result.class);
        Instant firstCreatedAt = Instant.parse("2026-09-24T10:00:00Z");
        Record first = historyRecord(FIRST_EVENT_ID, firstCreatedAt, 7L);
        Record second = mock(Record.class);
        when(result.size()).thenReturn(2);
        when(result.stream()).thenReturn(Stream.of(first, second));
        when(dsl.fetch(anyString(), any(Object[].class))).thenReturn(result);

        var page = service.history(DEAL_ID, new HistoryQuery(
            " contact_logged ", " contacted ", "sales-subject",
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-09-25T00:00:00Z"), FIRST_EVENT_ID, 1));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(FIRST_EVENT_ID);
            assertThat(item.activityType()).isEqualTo("CONTACT_LOGGED");
            assertThat(item.relatedActivityId()).isEqualTo(7L);
            assertThat(item.createdAt()).isEqualTo(firstCreatedAt);
        });
        assertThat(page.nextCursorCreatedAt()).isEqualTo(firstCreatedAt);
        assertThat(page.nextCursorId()).isEqualTo(FIRST_EVENT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(dsl).fetch(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("activity_type = ?", "outcome_code = ?", "created_by_subject = ?", "(created_at, id) < (?, ?)");
    }

    @Test
    void historyRejectsPartialCursor() {
        assertThatThrownBy(() -> service.history(DEAL_ID, new HistoryQuery(
            null, null, null, null, null, Instant.now(), null, 50)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("kursor vajab nii aega kui ID-d");
    }

    private static Record historyRecord(UUID id, Instant createdAt, Long relatedActivityId) {
        Record row = mock(Record.class);
        when(row.get("id", UUID.class)).thenReturn(id);
        when(row.get("activity_type", String.class)).thenReturn("CONTACT_LOGGED");
        when(row.get("source_type", String.class)).thenReturn("USER");
        when(row.get("content", String.class)).thenReturn("Helistati");
        when(row.get("contact_channel", String.class)).thenReturn("PHONE");
        when(row.get("outcome_code", String.class)).thenReturn("CONTACTED");
        when(row.get("reason", String.class)).thenReturn("Reached");
        when(row.get("created_by_subject", String.class)).thenReturn("sales-subject");
        when(row.get("created_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        when(row.get("related_reminder_id", Long.class)).thenReturn(relatedActivityId);
        when(row.get("deal_case_id", UUID.class)).thenReturn(DEAL_ID);
        return row;
    }
}
