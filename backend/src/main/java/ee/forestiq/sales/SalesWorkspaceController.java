package ee.forestiq.sales;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.sales.SalesWorkspaceService.OutcomeResult;
import ee.forestiq.sales.SalesWorkspaceService.SalesOutcome;
import ee.forestiq.sales.SalesWorkspaceService.SalesTask;

@RestController
@RequestMapping("/api/v1/sales-workspace")
@Tag(name = "Sales workspace", description = "Personal priority queue and low-administration call outcomes")
public class SalesWorkspaceController {

    private final SalesWorkspaceService service;

    public SalesWorkspaceController(SalesWorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    @Operation(summary = "Get the current seller's prioritized contact queue")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public List<SalesTask> queue() {
        return service.priorityQueue();
    }

    @PostMapping("/owners/{ownerId}/outcome")
    @Operation(summary = "Apply a standard contact outcome and automate follow-up work")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public OutcomeResult applyOutcome(@PathVariable UUID ownerId, @Valid @RequestBody OutcomeRequest request) {
        return service.applyOutcome(ownerId, request.outcome(), request.reason());
    }

    public record OutcomeRequest(@NotNull SalesOutcome outcome, @Size(max = 2000) String reason) { }
}
