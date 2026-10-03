package ee.forestiq.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.workflow.WorkflowDealPort;
import ee.forestiq.deal.workflow.WorkflowDealPort.DealReference;
import ee.forestiq.deal.workflow.WorkflowDealPort.TimelineEvent;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.WorkflowContactActivity;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.CreateCommand;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.WorkItem;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.workflow.WorkflowDtos.CompleteActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.ContactRequest;
import ee.forestiq.workflow.WorkflowDtos.DelegateActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.PostponeActivityRequest;

class WorkflowServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_DEAL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CONTACT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private OrganizationContext organizationContext;
    private OwnerCollaborationPort ownerCollaboration;
    private OrganizationIdentityDirectory identityDirectory;
    private ContactOutcomePolicy contactOutcomes;
    private WorkflowWorkItemPort workItems;
    private WorkflowDealPort deals;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        organizationContext = org.mockito.Mockito.mock(OrganizationContext.class);
        ownerCollaboration = org.mockito.Mockito.mock(OwnerCollaborationPort.class);
        identityDirectory = org.mockito.Mockito.mock(OrganizationIdentityDirectory.class);
        contactOutcomes = org.mockito.Mockito.mock(ContactOutcomePolicy.class);
        workItems = org.mockito.Mockito.mock(WorkflowWorkItemPort.class);
        deals = org.mockito.Mockito.mock(WorkflowDealPort.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(organizationContext.subject()).thenReturn("sales-subject");
        service = new WorkflowService(
            organizationContext,
            ownerCollaboration,
            identityDirectory,
            contactOutcomes,
            workItems,
            deals);
    }

    @Test
    void contactWithNextActivityUsesOwnedPortsAndAuditsOwnerAndDealTimelines() {
        Instant dueAt = Instant.now().plusSeconds(3600);
        when(contactOutcomes.requireActive("FOLLOW_UP")).thenReturn(
            new ContactOutcomePolicy.Outcome("FOLLOW_UP", "Vajab järeltegevust", false, true, true, 50));
        when(deals.requireDeal(DEAL_ID)).thenReturn(new DealReference(DEAL_ID, OWNER_ID));
        when(workItems.create(any())).thenReturn(41L);
        when(ownerCollaboration.recordWorkflowContact(eq(OWNER_ID), any())).thenReturn(CONTACT_ID);

        var result = service.createContact(OWNER_ID, new ContactRequest(
            "phone",
            "FOLLOW_UP",
            null,
            DEAL_ID,
            "Helista uuesti",
            dueAt,
            "caller-2",
            null,
            "Kokkulepitud järelkontakt",
            "follow_up"));

        assertThat(result.contactActivityId()).isEqualTo(CONTACT_ID);
        assertThat(result.activityId()).isEqualTo(41L);

        ArgumentCaptor<CreateCommand> create = ArgumentCaptor.forClass(CreateCommand.class);
        verify(workItems).create(create.capture());
        assertThat(create.getValue().ownerId()).isEqualTo(OWNER_ID);
        assertThat(create.getValue().dealId()).isEqualTo(DEAL_ID);
        assertThat(create.getValue().text()).isEqualTo("Helista uuesti");
        assertThat(create.getValue().dueTime()).isEqualTo(dueAt);
        assertThat(create.getValue().assigneeSubject()).isEqualTo("caller-2");
        assertThat(create.getValue().activityType()).isEqualTo("FOLLOW_UP");

        verify(identityDirectory).requireAssignableUser(eq(ORGANIZATION_ID), eq("caller-2"), any());

        ArgumentCaptor<WorkflowContactActivity> contact = ArgumentCaptor.forClass(WorkflowContactActivity.class);
        verify(ownerCollaboration).recordWorkflowContact(eq(OWNER_ID), contact.capture());
        assertThat(contact.getValue().dealCaseId()).isEqualTo(DEAL_ID);
        assertThat(contact.getValue().relatedReminderId()).isEqualTo(41L);
        assertThat(contact.getValue().contactChannel()).isEqualTo("PHONE");

        ArgumentCaptor<TimelineEvent> dealEvents = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(deals, org.mockito.Mockito.times(2)).appendEvent(eq(DEAL_ID), dealEvents.capture());
        assertThat(dealEvents.getAllValues()).extracting(TimelineEvent::eventType)
            .containsExactly("ACTIVITY_CREATED", "CONTACT_LOGGED");
    }

    @Test
    void existingActivityInfersItsDealForTheContact() {
        Instant dueAt = Instant.now().plusSeconds(1800);
        when(contactOutcomes.requireActive("CONTACTED")).thenReturn(
            new ContactOutcomePolicy.Outcome("CONTACTED", "Kontakt loodud", false, false, true, 10));
        when(workItems.requireOpen(9L)).thenReturn(
            new WorkItem(9L, OWNER_ID, DEAL_ID, "sales-subject", dueAt, "Helista", "PHONE", null));
        when(deals.requireDeal(DEAL_ID)).thenReturn(new DealReference(DEAL_ID, OWNER_ID));
        when(ownerCollaboration.recordWorkflowContact(eq(OWNER_ID), any())).thenReturn(CONTACT_ID);

        var result = service.createContact(OWNER_ID, new ContactRequest(
            "PHONE", "CONTACTED", null, null, null, null, null, 9L, null, null));

        assertThat(result.activityId()).isEqualTo(9L);
        verify(workItems, never()).create(any());

        ArgumentCaptor<WorkflowContactActivity> contact = ArgumentCaptor.forClass(WorkflowContactActivity.class);
        verify(ownerCollaboration).recordWorkflowContact(eq(OWNER_ID), contact.capture());
        assertThat(contact.getValue().dealCaseId()).isEqualTo(DEAL_ID);
        assertThat(contact.getValue().relatedReminderId()).isEqualTo(9L);
        verify(deals).appendEvent(eq(DEAL_ID), any());
    }

    @Test
    void existingActivityRejectsAConflictingDealLink() {
        when(contactOutcomes.requireActive("CONTACTED")).thenReturn(
            new ContactOutcomePolicy.Outcome("CONTACTED", "Kontakt loodud", false, false, true, 10));
        when(workItems.requireOpen(9L)).thenReturn(
            new WorkItem(9L, OWNER_ID, DEAL_ID, "sales-subject", Instant.now(), "Helista", "PHONE", null));

        assertThatThrownBy(() -> service.createContact(OWNER_ID, new ContactRequest(
            "PHONE", "CONTACTED", null, OTHER_DEAL_ID, null, null, null, 9L, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Kontakti tehing ja olemasoleva tegevuse tehing ei kattu");

        verify(deals, never()).appendEvent(any(), any());
        verify(ownerCollaboration, never()).recordWorkflowContact(any(), any());
    }

    @Test
    void outcomeThatRequiresNextActivityRejectsContactWithoutOne() {
        when(contactOutcomes.requireActive("NO_ANSWER")).thenReturn(
            new ContactOutcomePolicy.Outcome("NO_ANSWER", "Ei vastanud", false, true, true, 20));

        assertThatThrownBy(() -> service.createContact(OWNER_ID, new ContactRequest(
            "PHONE", "NO_ANSWER", null, null, null, null, null, null, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("järgmine tegevus");

        verify(ownerCollaboration, never()).recordWorkflowContact(any(), any());
    }

    @Test
    void completePostponeAndDelegateUseWorkItemPortAndRetainTimelineAudits() {
        Instant originalDue = Instant.now().plusSeconds(1800);
        Instant postponedDue = Instant.now().plusSeconds(7200);
        WorkItem activity = new WorkItem(
            7L, OWNER_ID, DEAL_ID, "sales-subject", originalDue, "Helista", "FOLLOW_UP", null);
        when(workItems.requireOpen(7L)).thenReturn(activity);

        service.completeActivity(7L, new CompleteActivityRequest(" valmis "));
        service.postponeActivity(7L, new PostponeActivityRequest(postponedDue, " hiljem "));
        service.delegateActivity(7L, new DelegateActivityRequest("caller-2", " koormus "));

        verify(workItems).complete(activity, "valmis");
        verify(workItems).postpone(activity, postponedDue, "hiljem");
        verify(workItems).delegate(activity, "caller-2", "koormus");
        verify(identityDirectory).requireAssignableUser(eq(ORGANIZATION_ID), eq("caller-2"), any());

        verify(ownerCollaboration, org.mockito.Mockito.times(3))
            .recordWorkflowReminderLifecycle(eq(OWNER_ID), any());

        ArgumentCaptor<TimelineEvent> events = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(deals, org.mockito.Mockito.times(3)).appendEvent(eq(DEAL_ID), events.capture());
        assertThat(events.getAllValues()).extracting(TimelineEvent::eventType)
            .containsExactly("ACTIVITY_COMPLETED", "ACTIVITY_POSTPONED", "ACTIVITY_DELEGATED");
    }

    @Test
    void activityMutationHonorsAssigneeScopeBeforeWriting() {
        WorkItem activity = new WorkItem(
            7L, OWNER_ID, DEAL_ID, "another-subject", Instant.now().plusSeconds(1800),
            "Helista", "FOLLOW_UP", null);
        when(workItems.requireOpen(7L)).thenReturn(activity);
        when(ownerCollaboration.hasOrganizationWideAccess()).thenReturn(false);

        assertThatThrownBy(() -> service.completeActivity(7L, new CompleteActivityRequest(null)))
            .isInstanceOf(AccessDeniedException.class);

        verify(workItems, never()).complete(any(), any());
        verify(deals, never()).appendEvent(any(), any());
    }

    @Test
    void dealHistoryDelegatesFilteringAndCursorProjectionToDealPort() {
        Instant createdAt = Instant.parse("2026-09-24T05:00:00Z");
        UUID eventId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(deals.requireDeal(DEAL_ID)).thenReturn(new DealReference(DEAL_ID, OWNER_ID));
        when(deals.history(eq(DEAL_ID), any())).thenReturn(new WorkflowDealPort.HistoryPage(
            List.of(new WorkflowDealPort.HistoryItem(
                eventId,
                "CONTACT_LOGGED",
                "USER",
                "Kontakt loodud",
                "PHONE",
                "CONTACTED",
                null,
                "sales-subject",
                createdAt,
                9L,
                DEAL_ID)),
            createdAt,
            eventId));

        var page = service.dealHistory(
            DEAL_ID, "CONTACT_LOGGED", "CONTACTED", "sales-subject",
            null, null, null, null, 25);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(eventId);
        assertThat(page.items().get(0).relatedActivityId()).isEqualTo(9L);
        assertThat(page.nextCursorCreatedAt()).isEqualTo(createdAt);
        assertThat(page.nextCursorId()).isEqualTo(eventId);
        verify(ownerCollaboration).requireReadable(OWNER_ID);

        ArgumentCaptor<WorkflowDealPort.HistoryQuery> query =
            ArgumentCaptor.forClass(WorkflowDealPort.HistoryQuery.class);
        verify(deals).history(eq(DEAL_ID), query.capture());
        assertThat(query.getValue().activityType()).isEqualTo("CONTACT_LOGGED");
        assertThat(query.getValue().outcome()).isEqualTo("CONTACTED");
        assertThat(query.getValue().requestedLimit()).isEqualTo(25);
    }
}
