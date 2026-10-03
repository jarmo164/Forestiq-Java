package ee.forestiq.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DealWorkDtos {
    private DealWorkDtos() {}

    public record HealthReason(
        String code,
        String severity,
        String title,
        String rule,
        Instant relevantAt,
        String recommendedAction
    ) {}

    public record DealWorkItem(
        UUID dealId,
        UUID ownerId,
        String ownerName,
        String stage,
        String saleSubject,
        List<String> cadastralCodes,
        BigDecimal value,
        String salesSubject,
        String evaluatorSubject,
        String nextAction,
        Instant nextActionDueAt,
        Instant evaluationDueAt,
        LocalDate offerValidUntil,
        Instant lastActivityAt,
        String health,
        List<HealthReason> healthReasons
    ) {}

    public record DealWorkResponse(List<DealWorkItem> items, long total) {}

    public record UpdateNextActionRequest(
        @NotBlank @Size(max = 1000) String nextAction,
        @NotNull Instant dueAt
    ) {}

    public record LossReasonDefinition(String code, String label, boolean active, int sortOrder) {}

    public record UpdateLossReasonRequest(
        @NotBlank @Size(max = 160) String label,
        boolean active,
        int sortOrder
    ) {}

    @Schema(name = "DealWorkLoseDealRequest")
    public record LoseDealRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_]+") String reasonCode,
        UUID rejectedEntryId,
        @Size(max = 4000) String note,
        Instant followUpAt
    ) {}

    public record LossOutcome(
        UUID dealId,
        String reasonCode,
        String reasonLabel,
        String lostFromStage,
        String salesSubject,
        String note,
        Instant followUpAt,
        String createdBy,
        Instant createdAt
    ) {}

    public record LossReportRow(String reasonCode, String reasonLabel, long count) {}
}
