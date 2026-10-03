package ee.forestiq.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SalesManagementDtos {
    private SalesManagementDtos() {}

    public record FunnelStep(
        String code,
        String label,
        long entered,
        long advanced,
        BigDecimal conversionRate,
        BigDecimal medianCycleHours
    ) {}

    public record FunnelReport(
        Instant from,
        Instant to,
        String salesSubject,
        List<FunnelStep> steps,
        long won,
        long lost,
        long cancelled,
        Instant generatedAt
    ) {}

    public record FunnelCase(
        UUID dealId,
        UUID ownerId,
        String ownerName,
        String salesSubject,
        String currentStage,
        Instant qualifiedAt,
        Instant evaluationAt,
        Instant offerAt,
        Instant outcomeAt,
        String outcome
    ) {}

    public record StageProbability(
        String stage,
        BigDecimal probability,
        int versionNo,
        Instant validFrom,
        Instant validTo,
        String createdBy
    ) {}

    public record UpdateStageProbabilityRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal probability
    ) {}

    public record ForecastItem(
        UUID dealId,
        UUID ownerId,
        String ownerName,
        String stage,
        String valueKind,
        BigDecimal inputValue,
        BigDecimal probability,
        int probabilityVersion,
        BigDecimal weightedValue,
        BigDecimal actualWonValue,
        String outcome
    ) {}

    public record ForecastReport(
        Instant from,
        Instant to,
        String salesSubject,
        BigDecimal totalInputValue,
        BigDecimal totalWeightedValue,
        BigDecimal totalWonValue,
        BigDecimal wonVariance,
        List<ForecastItem> items,
        Instant generatedAt
    ) {}

    public record SegmentRules(
        Set<String> statuses,
        String county,
        BigDecimal minForestAreaHectares,
        String contactState,
        String signalCode
    ) {}

    public record SaveSegmentRequest(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 1000) String description,
        @Valid @NotNull SegmentRules rules,
        @Size(max = 200) String recommendedWorkflow,
        boolean active
    ) {}

    public record SegmentSummary(
        UUID id,
        String name,
        String description,
        SegmentRules rules,
        String recommendedWorkflow,
        boolean active,
        Instant lastCalculatedAt,
        Integer lastCalculatedCount,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt
    ) {}

    public record SegmentMember(
        UUID ownerId,
        String ownerName,
        String status,
        String assignedToSubject,
        BigDecimal forestAreaHectares,
        String county,
        boolean hasContact
    ) {}

    public record SegmentRunResult(
        SegmentSummary segment,
        int total,
        List<SegmentMember> members,
        Instant calculatedAt
    ) {}

    public record AssignmentCandidate(
        String subject,
        String username,
        String name,
        List<String> roles,
        long assignedOwners,
        long openWork,
        long overdueWork,
        long dueNextSevenDays,
        long recentlyAssigned,
        BigDecimal loadScore,
        String explanation
    ) {}

    public record AssignmentSuggestion(
        UUID ownerId,
        String ownerName,
        String currentSubject,
        String recommendedSubject,
        String explanation
    ) {}

    public record AssignmentPreview(
        UUID segmentId,
        int ownerCount,
        List<AssignmentCandidate> candidates,
        List<AssignmentSuggestion> suggestions,
        String rule,
        String rationale,
        Instant generatedAt
    ) {}

    public record AssignmentDecision(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 100) String toSubject
    ) {}

    public record ApplyAssignmentsRequest(
        UUID segmentId,
        @NotBlank @Size(max = 64) String assignmentRule,
        @NotBlank @Size(max = 2000) String rationale,
        @NotEmpty List<@Valid AssignmentDecision> assignments
    ) {}

    public record AssignmentResultItem(UUID ownerId, String ownerName, String fromSubject, String toSubject, String result) {}

    public record AssignmentResult(
        UUID runId,
        int assignedCount,
        int reassignedCount,
        int unchangedCount,
        int skippedCount,
        List<AssignmentResultItem> items,
        Instant createdAt
    ) {}
}
