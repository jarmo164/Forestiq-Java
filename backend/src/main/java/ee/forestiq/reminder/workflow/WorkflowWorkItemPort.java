package ee.forestiq.reminder.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reminder-owned application API used by workflow orchestration.
 *
 * <p>The reminder module owns reminder/reminder_event persistence. Consumers receive immutable
 * work-item snapshots and express mutations as commands instead of issuing SQL.</p>
 */
public interface WorkflowWorkItemPort {

    WorkItem requireOpen(long workItemId);

    List<WorkItem> listOpenForOwner(UUID ownerId);

    long create(CreateCommand command);

    void complete(WorkItem workItem, String note);

    void postpone(WorkItem workItem, Instant newDueTime, String reason);

    void delegate(WorkItem workItem, String newAssigneeSubject, String reason);

    QueueSnapshot queue(QueueView view, QueueWindow window);

    record WorkItem(
        Long id,
        UUID ownerId,
        UUID dealId,
        String assigneeSubject,
        Instant dueTime,
        String text,
        String activityType,
        String priorityReason) {
    }

    record CreateCommand(
        UUID ownerId,
        UUID dealId,
        String text,
        Instant dueTime,
        String assigneeSubject,
        String activityType,
        String priorityReason) {
    }

    enum QueueView {
        TODAY,
        WEEK,
        OVERDUE
    }

    record QueueWindow(
        Instant now,
        Instant todayStart,
        Instant tomorrowStart,
        Instant sevenDaysEnd) {
    }

    record QueueItem(
        Long id,
        UUID ownerId,
        String ownerName,
        UUID dealId,
        String relatedDeal,
        String cadastralCode,
        String propertyName,
        String activityType,
        Instant dueTime,
        String priorityReason,
        String assigneeSubject,
        boolean delegated) {
    }

    record QueueCounts(long today, long nextSevenDays, long overdue) {
    }

    record QueueSnapshot(QueueView view, List<QueueItem> items, QueueCounts counts) {
        public QueueSnapshot {
            items = List.copyOf(items);
        }
    }
}
