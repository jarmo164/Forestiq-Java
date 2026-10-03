package ee.forestiq.owner.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application-facing collaboration boundary for state owned by the owner module.
 *
 * <p>Consumers may request owner access, express lifecycle intent, record a
 * typed timeline item, or apply management assignment commands. They must not
 * use owner HTTP DTOs, entities, repositories, or persistence tables.</p>
 */
public interface OwnerCollaborationPort {

    OwnerAccess requireReadable(UUID ownerId);

    OwnerAccess requireMutable(UUID ownerId);

    void assertDealCreationAllowed(UUID ownerId);

    Optional<String> assignedSubjectRestriction();

    boolean hasOrganizationWideAccess();

    void requireAssignmentManagementAccess();

    void applyDealLifecycle(UUID ownerId, DealLifecycleTransition transition);

    void appendDealSystemActivity(UUID ownerId, String content);

    UUID recordWorkflowContact(UUID ownerId, WorkflowContactActivity activity);

    void recordWorkflowReminderLifecycle(UUID ownerId, WorkflowReminderLifecycleActivity activity);

    WorkflowHistoryPage workflowHistory(UUID ownerId, WorkflowHistoryQuery query);

    List<ManagementAssignmentOutcome> applyManagementAssignments(
        List<ManagementAssignment> assignments,
        String rationale);

    record OwnerAccess(
        UUID ownerId,
        String displayName,
        String status,
        String assignedToSubject,
        String phone,
        String email,
        String address) {
    }

    enum DealLifecycleTransition {
        QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION,
        REQUEST_EVALUATION,
        COMPLETE_EVALUATION_NEEDS_DATA,
        COMPLETE_EVALUATION_VALUED,
        START_NEGOTIATION,
        ENSURE_NEGOTIATION_AFTER_COUNTEROFFER
    }

    record WorkflowContactActivity(
        String activityType,
        String content,
        String contactChannel,
        String outcomeCode,
        String reason,
        UUID dealCaseId,
        Long relatedReminderId) {
    }

    record WorkflowReminderLifecycleActivity(
        String content,
        UUID dealCaseId,
        Long relatedReminderId) {
    }

    record WorkflowHistoryQuery(
        String activityType,
        String outcome,
        String author,
        Instant from,
        Instant to,
        Instant cursorCreatedAt,
        UUID cursorId,
        int requestedLimit) {
    }

    record WorkflowHistoryItem(
        UUID id,
        String activityType,
        String sourceType,
        String content,
        String contactChannel,
        String outcomeCode,
        String reason,
        String createdBySubject,
        Instant createdAt,
        Long relatedReminderId,
        UUID dealCaseId) {
    }

    record WorkflowHistoryPage(
        List<WorkflowHistoryItem> items,
        Instant nextCursorCreatedAt,
        UUID nextCursorId) {

        public WorkflowHistoryPage {
            items = List.copyOf(items);
        }
    }

    record ManagementAssignment(UUID ownerId, String toSubject) {
    }

    record ManagementAssignmentOutcome(
        UUID ownerId,
        String ownerName,
        String fromSubject,
        String toSubject,
        boolean changed) {
    }
}
