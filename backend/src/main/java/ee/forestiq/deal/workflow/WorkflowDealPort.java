package ee.forestiq.deal.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deal-owned application API used by workflow orchestration and history reads. */
public interface WorkflowDealPort {

    DealReference requireDeal(UUID dealId);

    void appendEvent(UUID dealId, TimelineEvent event);

    HistoryPage history(UUID dealId, HistoryQuery query);

    record DealReference(UUID dealId, UUID ownerId) {
    }

    record TimelineEvent(
        String eventType,
        String description,
        String sourceType,
        String channel,
        String outcomeCode,
        String reason,
        Long activityId) {
    }

    record HistoryQuery(
        String activityType,
        String outcome,
        String author,
        Instant from,
        Instant to,
        Instant cursorCreatedAt,
        UUID cursorId,
        int requestedLimit) {
    }

    record HistoryItem(
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
        UUID dealId) {
    }

    record HistoryPage(List<HistoryItem> items, Instant nextCursorCreatedAt, UUID nextCursorId) {
        public HistoryPage {
            items = List.copyOf(items);
        }
    }
}
