package ee.forestiq.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WorkflowDtos {
    private WorkflowDtos() {}

    public record OutcomeDefinition(String code, String label, boolean requiresReason, boolean requiresNextActivity,
                                    boolean active, int sortOrder) {}

    public record UpdateOutcomeRequest(
        @NotBlank @Size(max = 160) String label,
        boolean requiresReason,
        boolean requiresNextActivity,
        boolean active,
        int sortOrder
    ) {}

    public record AssigneeOption(String subject, String username, String name, List<String> roles) {}

    public record OpenActivityOption(
        Long id,
        String text,
        String activityType,
        Instant dueTime,
        String assigneeSubject,
        UUID dealId
    ) {}

    public record ContactRequest(
        @NotBlank String channel,
        @NotBlank String outcomeCode,
        @Size(max = 2000) String reason,
        UUID dealId,
        @Size(max = 500) String nextStep,
        Instant nextStepDueAt,
        String assigneeSubject,
        Long existingActivityId,
        @Size(max = 300) String priorityReason,
        @Size(max = 40) String activityType
    ) {}

    public record ContactResult(UUID contactActivityId, Long activityId) {}

    public record HistoryItem(
        UUID id,
        String activityType,
        String sourceType,
        String content,
        String channel,
        String outcomeCode,
        String reason,
        String createdBy,
        Instant createdAt,
        Long relatedActivityId,
        UUID dealId
    ) {}

    public record HistoryPage(List<HistoryItem> items, Instant nextCursorCreatedAt, UUID nextCursorId) {}

    public record CompleteActivityRequest(@Size(max = 1000) String note) {}

    public record PostponeActivityRequest(
        @NotNull Instant dueTime,
        @NotBlank @Size(max = 1000) String reason
    ) {}

    public record DelegateActivityRequest(
        @NotBlank String assigneeSubject,
        @NotBlank @Size(max = 1000) String reason
    ) {}

    public record WorkQueueItem(
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
        boolean delegated
    ) {}

    public record WorkQueueCounts(long today, long nextSevenDays, long overdue) {}

    public record WorkQueueResponse(String view, List<WorkQueueItem> items, WorkQueueCounts counts) {}
}
