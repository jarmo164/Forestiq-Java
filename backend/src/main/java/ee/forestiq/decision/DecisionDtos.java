package ee.forestiq.decision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DecisionDtos {
    private DecisionDtos() {}

    public record PortfolioSource(
        String source,
        Instant observedAt,
        boolean available,
        String note
    ) {}

    public record PortfolioParcel(
        UUID id,
        String cadastralCode,
        String name,
        BigDecimal areaHectares,
        BigDecimal forestAreaHectares,
        BigDecimal knownVolumeCubicMeters,
        long activeNotificationCount,
        String county,
        Instant updatedAt
    ) {}

    public record GeographicGroup(String county, long parcelCount, BigDecimal areaHectares) {}

    public record PortfolioBrief(
        UUID ownerId,
        String ownerName,
        int parcelCount,
        BigDecimal totalAreaHectares,
        BigDecimal forestAreaHectares,
        BigDecimal knownVolumeCubicMeters,
        int volumeKnownParcelCount,
        long activeNotificationCount,
        List<PortfolioParcel> parcels,
        List<GeographicGroup> geography,
        List<PortfolioSource> sources,
        Instant generatedAt
    ) {}

    public record DecisionSignal(
        String code,
        String severity,
        String title,
        String reason,
        String source,
        Instant observedAt,
        String recommendedAction,
        UUID parcelId,
        String cadastralCode,
        UUID dealId,
        boolean legalDecision
    ) {}

    public record EvidencePreview(
        UUID dealId,
        UUID ownerId,
        Instant generatedAt,
        List<UUID> selectedParcelIds,
        PortfolioBrief portfolio,
        List<DecisionSignal> signals
    ) {}

    public record EvidenceSnapshot(
        UUID id,
        UUID dealId,
        UUID ownerId,
        String state,
        int schemaVersion,
        JsonNode snapshot,
        Instant sourceObservedAt,
        String createdBy,
        Instant createdAt,
        String confirmedBy,
        Instant confirmedAt
    ) {}

    public record SaveWorkbasketRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String purpose,
        boolean dynamic,
        Map<String, String> filters,
        List<@NotBlank String> cadastralCodes,
        List<UUID> ownerIds
    ) {}

    public record WorkbasketParcel(UUID id, String cadastralCode, String name) {}
    public record WorkbasketOwner(UUID id, String name) {}

    public record Workbasket(
        UUID id,
        String name,
        String purpose,
        String selectionMode,
        Map<String, String> filters,
        List<WorkbasketParcel> parcels,
        List<WorkbasketOwner> owners,
        int hiddenItemCount,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ShareWorkbasketRequest(
        String recipientSubject,
        @Pattern(regexp = "ORG_ADMIN|CRM_MANAGER|EVALUATOR|CALLER|VIEWER") String recipientRole,
        @NotNull @Pattern(regexp = "READ|EDIT") String permission,
        @NotBlank @Size(max = 1000) String purpose,
        @NotNull Instant dueAt
    ) {}

    public record WorkbasketHandoff(
        UUID id,
        UUID workbasketId,
        String workbasketName,
        String recipientSubject,
        String recipientRole,
        String permission,
        String purpose,
        Instant dueAt,
        String status,
        String createdBy,
        Instant createdAt,
        Instant acceptedAt,
        int hiddenItemCount
    ) {}

    public record WorkbasketHandoffView(WorkbasketHandoff handoff, Workbasket workbasket) {}
}
