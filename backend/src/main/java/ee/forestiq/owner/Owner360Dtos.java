package ee.forestiq.owner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ee.forestiq.decision.DecisionDtos.DecisionSignal;

/**
 * Canonical, permission-aware read contract for the owner 360 workspace.
 *
 * The overview is intentionally small so it can render before the heavier
 * relation/history sections. Missing values stay null and are never coerced to
 * zero by this contract.
 */
public final class Owner360Dtos {
    private Owner360Dtos() {}

    public record OwnerIdentity(
        UUID id,
        String displayName,
        boolean sensitiveDetailsVisible,
        String personalOrRegistryCode,
        String ownerType,
        String status,
        String email,
        String phone,
        String address,
        String info,
        String assignedTo,
        Instant statusSetAt,
        Instant updatedAt
    ) {}

    public record PortfolioKpis(
        Integer activeParcelCount,
        Integer historicalParcelCount,
        BigDecimal totalAreaHectares,
        BigDecimal forestAreaHectares,
        BigDecimal knownVolumeCubicMeters,
        Integer volumeKnownParcelCount,
        Long activeNotificationCount
    ) {}

    public record SourceInfo(
        String source,
        Instant observedAt,
        String freshness,
        boolean available,
        String note
    ) {}

    public record SectionState(String state, String errorCode) {
        public static SectionState ready() { return new SectionState("READY", null); }
        public static SectionState error(String errorCode) { return new SectionState("ERROR", errorCode); }
    }

    public record Owner360Overview(
        OwnerIdentity owner,
        PortfolioKpis portfolio,
        List<DecisionSignal> signals,
        List<SourceInfo> sources,
        Map<String, SectionState> sections,
        Instant generatedAt
    ) {}

    public record Coordinates(double longitude, double latitude) {}

    public record PortfolioMapParcel(
        UUID parcelId,
        String cadastralCode,
        String name,
        BigDecimal areaHectares,
        Coordinates centroid
    ) {}

    public record ParcelRelation(
        UUID parcelId,
        String cadastralCode,
        String name,
        String county,
        String municipality,
        BigDecimal areaHectares,
        BigDecimal forestAreaHectares,
        boolean active,
        String source,
        Instant sourceSeenAt,
        Instant deactivatedAt,
        int activeOwnerCount,
        BigDecimal ownershipShare,
        Coordinates centroid
    ) {}

    public record ParcelRelationsPage(
        List<ParcelRelation> content,
        int offset,
        int limit,
        boolean hasMore
    ) {}

    public record TimelineEvent(
        String id,
        String category,
        String type,
        String title,
        String description,
        Instant createdAt,
        String source,
        String createdBy,
        UUID parcelId,
        String cadastralCode
    ) {}

    public record TimelinePage(
        List<TimelineEvent> content,
        int offset,
        int limit,
        boolean hasMore
    ) {}

    public record WorkflowSummary(
        String ownerStatus,
        String assignedTo,
        boolean businessDetailsVisible,
        Integer activeDealCount,
        UUID latestDealId,
        String latestDealStage,
        String nextAction,
        Instant nextActionDueAt,
        Integer openReminderCount,
        Instant nextReminderAt
    ) {}
}