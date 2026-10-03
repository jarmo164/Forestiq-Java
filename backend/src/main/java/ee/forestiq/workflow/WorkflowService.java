package ee.forestiq.workflow;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.workflow.WorkflowDealPort;
import ee.forestiq.deal.workflow.WorkflowDealPort.DealReference;
import ee.forestiq.deal.workflow.WorkflowDealPort.HistoryQuery;
import ee.forestiq.deal.workflow.WorkflowDealPort.TimelineEvent;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.WorkflowContactActivity;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.WorkflowHistoryQuery;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.WorkflowReminderLifecycleActivity;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.CreateCommand;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.QueueView;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.QueueWindow;
import ee.forestiq.reminder.workflow.WorkflowWorkItemPort.WorkItem;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.workflow.WorkflowDtos.AssigneeOption;
import ee.forestiq.workflow.WorkflowDtos.CompleteActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.ContactRequest;
import ee.forestiq.workflow.WorkflowDtos.ContactResult;
import ee.forestiq.workflow.WorkflowDtos.DelegateActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.HistoryItem;
import ee.forestiq.workflow.WorkflowDtos.HistoryPage;
import ee.forestiq.workflow.WorkflowDtos.OpenActivityOption;
import ee.forestiq.workflow.WorkflowDtos.OutcomeDefinition;
import ee.forestiq.workflow.WorkflowDtos.PostponeActivityRequest;
import ee.forestiq.workflow.WorkflowDtos.UpdateOutcomeRequest;
import ee.forestiq.workflow.WorkflowDtos.WorkQueueCounts;
import ee.forestiq.workflow.WorkflowDtos.WorkQueueItem;
import ee.forestiq.workflow.WorkflowDtos.WorkQueueResponse;

@Service
public class WorkflowService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Tallinn");
    private static final Set<OrganizationRole> ASSIGNABLE_ROLES = Set.of(
        OrganizationRole.CALLER,
        OrganizationRole.CRM_MANAGER,
        OrganizationRole.ORG_ADMIN,
        OrganizationRole.EVALUATOR);
    private static final Set<String> CHANNELS = Set.of("PHONE", "EMAIL", "MEETING", "OTHER");

    private final OrganizationContext organizationContext;
    private final OwnerCollaborationPort ownerCollaboration;
    private final OrganizationIdentityDirectory identityDirectory;
    private final ContactOutcomePolicy contactOutcomes;
    private final WorkflowWorkItemPort workItems;
    private final WorkflowDealPort deals;

    public WorkflowService(
        OrganizationContext organizationContext,
        OwnerCollaborationPort ownerCollaboration,
        OrganizationIdentityDirectory identityDirectory,
        ContactOutcomePolicy contactOutcomes,
        WorkflowWorkItemPort workItems,
        WorkflowDealPort deals) {
        this.organizationContext = organizationContext;
        this.ownerCollaboration = ownerCollaboration;
        this.identityDirectory = identityDirectory;
        this.contactOutcomes = contactOutcomes;
        this.workItems = workItems;
        this.deals = deals;
    }

    public List<OutcomeDefinition> listOutcomes() {
        return contactOutcomes.list().stream()
            .map(WorkflowService::outcomeDefinition)
            .toList();
    }

    @Transactional
    public OutcomeDefinition updateOutcome(String rawCode, UpdateOutcomeRequest request) {
        return outcomeDefinition(contactOutcomes.update(rawCode, new ContactOutcomePolicy.Update(
            request.label(),
            request.requiresReason(),
            request.requiresNextActivity(),
            request.active(),
            request.sortOrder())));
    }

    public List<AssigneeOption> listAssignees() {
        UUID organizationId = organizationContext.requiredOrganizationId();
        return identityDirectory.listActiveOrganizationUsers(organizationId).stream()
            .filter(user -> user.roles().stream().anyMatch(ASSIGNABLE_ROLES::contains))
            .map(user -> new AssigneeOption(
                user.subject(),
                user.username(),
                user.displayName(),
                user.roles().stream().map(OrganizationRole::name).sorted().toList()))
            .toList();
    }

    public List<OpenActivityOption> openActivities(UUID ownerId) {
        ownerCollaboration.requireReadable(ownerId);
        return workItems.listOpenForOwner(ownerId).stream()
            .map(item -> new OpenActivityOption(
                item.id(),
                item.text(),
                item.activityType(),
                item.dueTime(),
                item.assigneeSubject(),
                item.dealId()))
            .toList();
    }

    @Transactional
    public ContactResult createContact(UUID ownerId, ContactRequest request) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        String actor = organizationContext.subject();
        ownerCollaboration.requireMutable(ownerId);

        String channel = normalizeChannel(request.channel());
        ContactOutcomePolicy.Outcome outcome = contactOutcomes.requireActive(request.outcomeCode());
        String reason = trimToNull(request.reason());
        if (outcome.requiresReason() && reason == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Selle kontakti tulemuse puhul on põhjendus kohustuslik.");
        }

        UUID dealId = request.dealId();
        Long activityId = null;
        WorkItem existingActivity = null;
        if (request.existingActivityId() != null) {
            existingActivity = workItems.requireOpen(request.existingActivityId());
            if (!ownerId.equals(existingActivity.ownerId())) {
                throw new ResponseStatusException(BAD_REQUEST, "Valitud tegevus ei kuulu sellele omanikule.");
            }
            if (existingActivity.dealId() != null && dealId != null && !existingActivity.dealId().equals(dealId)) {
                throw new ResponseStatusException(BAD_REQUEST,
                    "Kontakti tehing ja olemasoleva tegevuse tehing ei kattu.");
            }
            if (dealId == null) {
                dealId = existingActivity.dealId();
            }
        }

        if (dealId != null) {
            requireDealForOwner(dealId, ownerId);
        }

        if (existingActivity != null) {
            activityId = existingActivity.id();
        } else if (StringUtils.hasText(request.nextStep())) {
            if (request.nextStepDueAt() == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Järgmise tegevuse tähtaeg on kohustuslik.");
            }
            String assignee = StringUtils.hasText(request.assigneeSubject())
                ? request.assigneeSubject().trim()
                : actor;
            if (!actor.equals(assignee)) {
                identityDirectory.requireAssignableUser(organizationId, assignee, ASSIGNABLE_ROLES);
            }

            String activityType = StringUtils.hasText(request.activityType())
                ? request.activityType().trim().toUpperCase(Locale.ROOT)
                : "FOLLOW_UP";
            activityId = workItems.create(new CreateCommand(
                ownerId,
                dealId,
                request.nextStep().trim(),
                request.nextStepDueAt(),
                assignee,
                activityType,
                trimToNull(request.priorityReason())));

            String activityText = "Järgmine tegevus loodud: " + request.nextStep().trim();
            ownerCollaboration.recordWorkflowReminderLifecycle(
                ownerId,
                new WorkflowReminderLifecycleActivity(activityText, dealId, activityId));
            if (dealId != null) {
                deals.appendEvent(dealId, new TimelineEvent(
                    "ACTIVITY_CREATED",
                    request.nextStep().trim(),
                    "USER",
                    null,
                    null,
                    null,
                    activityId));
            }
        } else if (request.nextStepDueAt() != null || StringUtils.hasText(request.assigneeSubject())) {
            throw new ResponseStatusException(
                BAD_REQUEST,
                "Tähtaega või vastutajat saab määrata ainult koos järgmise tegevusega.");
        }

        if (outcome.requiresNextActivity() && activityId == null) {
            throw new ResponseStatusException(
                BAD_REQUEST,
                "Selle kontakti tulemuse puhul on järgmine tegevus koos tähtaja ja vastutajaga kohustuslik.");
        }

        String activityType = switch (channel) {
            case "PHONE" -> "PHONE";
            case "EMAIL" -> "EMAIL";
            case "MEETING" -> "MEETING";
            default -> "NOTE";
        };
        String content = outcome.label() + (reason == null ? "" : ": " + reason);
        UUID contactId = ownerCollaboration.recordWorkflowContact(
            ownerId,
            new WorkflowContactActivity(
                activityType,
                content,
                channel,
                outcome.code(),
                reason,
                dealId,
                activityId));

        if (dealId != null) {
            deals.appendEvent(dealId, new TimelineEvent(
                "CONTACT_LOGGED",
                content,
                "USER",
                channel,
                outcome.code(),
                reason,
                activityId));
        }
        return new ContactResult(contactId, activityId);
    }

    public HistoryPage ownerHistory(
        UUID ownerId,
        String activityType,
        String outcome,
        String author,
        Instant from,
        Instant to,
        Instant cursorCreatedAt,
        UUID cursorId,
        int requestedLimit) {
        return historyPage(ownerCollaboration.workflowHistory(
            ownerId,
            new WorkflowHistoryQuery(
                activityType,
                outcome,
                author,
                from,
                to,
                cursorCreatedAt,
                cursorId,
                requestedLimit)));
    }

    public HistoryPage dealHistory(
        UUID dealId,
        String activityType,
        String outcome,
        String author,
        Instant from,
        Instant to,
        Instant cursorCreatedAt,
        UUID cursorId,
        int requestedLimit) {
        DealReference deal = deals.requireDeal(dealId);
        ownerCollaboration.requireReadable(deal.ownerId());
        return historyPage(deals.history(
            dealId,
            new HistoryQuery(
                activityType,
                outcome,
                author,
                from,
                to,
                cursorCreatedAt,
                cursorId,
                requestedLimit)));
    }

    public WorkQueueResponse workQueue(String rawView) {
        QueueView view = normalizeView(rawView);
        WorkflowWorkItemPort.QueueSnapshot snapshot = workItems.queue(view, queueWindow());
        List<WorkQueueItem> items = snapshot.items().stream()
            .map(item -> new WorkQueueItem(
                item.id(),
                item.ownerId(),
                item.ownerName(),
                item.dealId(),
                item.relatedDeal(),
                item.cadastralCode(),
                item.propertyName(),
                item.activityType(),
                item.dueTime(),
                item.priorityReason(),
                item.assigneeSubject(),
                item.delegated()))
            .toList();
        return new WorkQueueResponse(
            snapshot.view().name(),
            items,
            new WorkQueueCounts(
                snapshot.counts().today(),
                snapshot.counts().nextSevenDays(),
                snapshot.counts().overdue()));
    }

    @Transactional
    public void completeActivity(long activityId, CompleteActivityRequest request) {
        mutateActivity(activityId, Mutation.COMPLETED, null, request.note(), null);
    }

    @Transactional
    public void postponeActivity(long activityId, PostponeActivityRequest request) {
        if (!request.dueTime().isAfter(Instant.now())) {
            throw new ResponseStatusException(BAD_REQUEST, "Uus tähtaeg peab olema tulevikus.");
        }
        mutateActivity(activityId, Mutation.POSTPONED, request.dueTime(), request.reason(), null);
    }

    @Transactional
    public void delegateActivity(long activityId, DelegateActivityRequest request) {
        String newAssignee = request.assigneeSubject().trim();
        identityDirectory.requireAssignableUser(
            organizationContext.requiredOrganizationId(),
            newAssignee,
            ASSIGNABLE_ROLES);
        mutateActivity(activityId, Mutation.DELEGATED, null, request.reason(), newAssignee);
    }

    private void mutateActivity(
        long activityId,
        Mutation mutation,
        Instant newDueTime,
        String reason,
        String newAssignee) {
        WorkItem activity = workItems.requireOpen(activityId);
        String actor = organizationContext.subject();
        if (!actor.equals(activity.assigneeSubject()) && !ownerCollaboration.hasOrganizationWideAccess()) {
            throw new AccessDeniedException("Activity is not assigned to the current user");
        }
        if (activity.ownerId() != null) {
            ownerCollaboration.requireMutable(activity.ownerId());
        }

        String normalizedReason = trimToNull(reason);
        switch (mutation) {
            case COMPLETED -> workItems.complete(activity, normalizedReason);
            case POSTPONED -> workItems.postpone(activity, newDueTime, normalizedReason);
            case DELEGATED -> {
                if (activity.assigneeSubject().equals(newAssignee)) {
                    throw new ResponseStatusException(BAD_REQUEST, "Tegevus on juba sellele kasutajale määratud.");
                }
                workItems.delegate(activity, newAssignee, normalizedReason);
            }
        }

        String text = switch (mutation) {
            case COMPLETED -> "Tegevus lõpetatud" + suffix(normalizedReason);
            case POSTPONED -> "Tegevus edasi lükatud: " + activity.dueTime() + " → " + newDueTime
                + suffix(normalizedReason);
            case DELEGATED -> "Tegevus delegeeritud: " + activity.assigneeSubject() + " → " + newAssignee
                + suffix(normalizedReason);
        };

        if (activity.ownerId() != null) {
            ownerCollaboration.recordWorkflowReminderLifecycle(
                activity.ownerId(),
                new WorkflowReminderLifecycleActivity(text, activity.dealId(), activity.id()));
        }
        if (activity.dealId() != null) {
            deals.appendEvent(activity.dealId(), new TimelineEvent(
                "ACTIVITY_" + mutation.name(),
                text,
                "USER",
                null,
                null,
                normalizedReason,
                activity.id()));
        }
    }

    private void requireDealForOwner(UUID dealId, UUID ownerId) {
        DealReference deal = deals.requireDeal(dealId);
        if (!ownerId.equals(deal.ownerId())) {
            throw new ResponseStatusException(BAD_REQUEST, "Tehing ei kuulu sellele omanikule.");
        }
    }

    private static OutcomeDefinition outcomeDefinition(ContactOutcomePolicy.Outcome outcome) {
        return new OutcomeDefinition(
            outcome.code(),
            outcome.label(),
            outcome.requiresReason(),
            outcome.requiresNextActivity(),
            outcome.active(),
            outcome.sortOrder());
    }

    private static HistoryPage historyPage(OwnerCollaborationPort.WorkflowHistoryPage page) {
        return new HistoryPage(
            page.items().stream()
                .map(item -> new HistoryItem(
                    item.id(),
                    item.activityType(),
                    item.sourceType(),
                    item.content(),
                    item.contactChannel(),
                    item.outcomeCode(),
                    item.reason(),
                    item.createdBySubject(),
                    item.createdAt(),
                    item.relatedReminderId(),
                    item.dealCaseId()))
                .toList(),
            page.nextCursorCreatedAt(),
            page.nextCursorId());
    }

    private static HistoryPage historyPage(WorkflowDealPort.HistoryPage page) {
        return new HistoryPage(
            page.items().stream()
                .map(item -> new HistoryItem(
                    item.id(),
                    item.activityType(),
                    item.sourceType(),
                    item.content(),
                    item.channel(),
                    item.outcomeCode(),
                    item.reason(),
                    item.createdBy(),
                    item.createdAt(),
                    item.relatedActivityId(),
                    item.dealId()))
                .toList(),
            page.nextCursorCreatedAt(),
            page.nextCursorId());
    }

    private static QueueWindow queueWindow() {
        ZonedDateTime now = ZonedDateTime.now(BUSINESS_ZONE);
        ZonedDateTime start = now.toLocalDate().atStartOfDay(BUSINESS_ZONE);
        return new QueueWindow(
            now.toInstant(),
            start.toInstant(),
            start.plusDays(1).toInstant(),
            start.plusDays(7).toInstant());
    }

    private static String normalizeChannel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new ResponseStatusException(
                BAD_REQUEST,
                "Kontakti kanal peab olema PHONE, EMAIL, MEETING või OTHER.");
        }
        return normalized;
    }

    private static QueueView normalizeView(String value) {
        String normalized = value == null ? "TODAY" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TODAY", "DAY" -> QueueView.TODAY;
            case "WEEK", "NEXT_7_DAYS", "NEXT7" -> QueueView.WEEK;
            case "OVERDUE" -> QueueView.OVERDUE;
            default -> throw new ResponseStatusException(BAD_REQUEST, "Vaade peab olema TODAY, WEEK või OVERDUE.");
        };
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String suffix(String reason) {
        return StringUtils.hasText(reason) ? ": " + reason.trim() : "";
    }

    private enum Mutation {
        COMPLETED,
        POSTPONED,
        DELEGATED
    }
}
