package ee.forestiq.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import ee.forestiq.deal.DealWorkDtos.DealWorkItem;
import ee.forestiq.deal.DealWorkDtos.DealWorkResponse;
import ee.forestiq.deal.DealWorkDtos.LoseDealRequest;
import ee.forestiq.deal.DealWorkDtos.LossOutcome;
import ee.forestiq.deal.DealWorkDtos.LossReasonDefinition;
import ee.forestiq.deal.DealWorkDtos.LossReportRow;
import ee.forestiq.deal.DealWorkDtos.UpdateLossReasonRequest;
import ee.forestiq.deal.DealWorkDtos.UpdateNextActionRequest;
import ee.forestiq.deal.DealLifecycleService.LoseDealCommand;
import ee.forestiq.deal.DealLifecycleService.UpdateLossReasonCommand;
import ee.forestiq.deal.DealLifecycleService.UpdateNextActionCommand;

@RestController
@RequestMapping("/api/v1/deal-work")
@Tag(name = "Deal work", description = "Active deal dashboard, explainable health and structured loss outcomes")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER','EVALUATOR')")
public class DealWorkController {
    private final DealWorkService service;
    private final DealLifecycleService lifecycleService;

    public DealWorkController(DealWorkService service, DealLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/active")
    @Operation(summary = "List active authorized deals with value, next action and explainable health")
    public DealWorkResponse active(
        @RequestParam(required = false) String stage,
        @RequestParam(required = false) String assignee,
        @RequestParam(required = false) String health,
        @RequestParam(required = false) String due,
        @RequestParam(required = false) BigDecimal minValue,
        @RequestParam(required = false) BigDecimal maxValue
    ) {
        return service.list(stage, assignee, health, due, minValue, maxValue);
    }

    @GetMapping("/{dealId}")
    @Operation(summary = "Open one active deal in the same scoped work context")
    public DealWorkItem deal(@PathVariable UUID dealId) { return service.get(dealId); }

    @PutMapping("/{dealId}/next-action")
    @Operation(summary = "Update the critical next action without leaving the deal-work context")
    public DealWorkItem updateNextAction(@PathVariable UUID dealId, @Valid @RequestBody UpdateNextActionRequest request) {
        lifecycleService.updateNextAction(dealId, new UpdateNextActionCommand(request.nextAction(), request.dueAt()));
        return service.get(dealId);
    }

    @GetMapping("/loss-reasons")
    @Operation(summary = "List organization-managed standard loss reasons")
    public List<LossReasonDefinition> lossReasons(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.lossReasons(includeInactive);
    }

    @PutMapping("/loss-reasons/{code}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @Operation(summary = "Create or update an organization loss reason")
    public LossReasonDefinition updateLossReason(@PathVariable String code, @Valid @RequestBody UpdateLossReasonRequest request) {
        return lifecycleService.updateLossReason(code,
            new UpdateLossReasonCommand(request.label(), request.active(), request.sortOrder()));
    }

    @PostMapping("/{dealId}/lose")
    @Operation(summary = "Close a deal as lost with a standard reason and optional follow-up activity")
    public LossOutcome lose(@PathVariable UUID dealId, @Valid @RequestBody LoseDealRequest request) {
        return lifecycleService.lose(dealId,
            new LoseDealCommand(request.reasonCode(), request.rejectedEntryId(), request.note(), request.followUpAt()));
    }

    @GetMapping("/loss-report")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @Operation(summary = "Aggregate lost deals by standard reason with optional period, sales-person and prior-stage filters")
    public List<LossReportRow> lossReport(@RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(required = false) String salesSubject,
                                          @RequestParam(required = false) String lostFromStage) {
        return service.lossReport(from, to, salesSubject, lostFromStage);
    }
}
