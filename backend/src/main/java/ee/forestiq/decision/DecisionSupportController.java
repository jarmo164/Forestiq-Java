package ee.forestiq.decision;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import ee.forestiq.decision.DecisionDtos.DecisionSignal;
import ee.forestiq.decision.DecisionDtos.EvidencePreview;
import ee.forestiq.decision.DecisionDtos.EvidenceSnapshot;
import ee.forestiq.decision.DecisionDtos.PortfolioBrief;
import ee.forestiq.decision.DecisionDtos.SaveWorkbasketRequest;
import ee.forestiq.decision.DecisionDtos.ShareWorkbasketRequest;
import ee.forestiq.decision.DecisionDtos.Workbasket;
import ee.forestiq.decision.DecisionDtos.WorkbasketHandoff;
import ee.forestiq.decision.DecisionDtos.WorkbasketHandoffView;

@RestController
@RequestMapping("/api/v1/decision-support")
@Tag(name = "Decision support", description = "Portfolio briefs, explainable signals, evidence snapshots and saved map workbaskets")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
public class DecisionSupportController {

    private final PortfolioDecisionService portfolioService;
    private final SavedWorkbasketService workbasketService;
    private final WorkbasketHandoffService handoffService;
    private final SharedWorkbasketEditService sharedEditService;

    public DecisionSupportController(PortfolioDecisionService portfolioService, SavedWorkbasketService workbasketService,
                                     WorkbasketHandoffService handoffService, SharedWorkbasketEditService sharedEditService) {
        this.portfolioService = portfolioService;
        this.workbasketService = workbasketService;
        this.handoffService = handoffService;
        this.sharedEditService = sharedEditService;
    }

    @GetMapping("/owners/{ownerId}/portfolio")
    @Operation(summary = "Get a source-aware current portfolio brief for an owner")
    public PortfolioBrief portfolio(@PathVariable UUID ownerId) { return portfolioService.portfolio(ownerId); }

    @GetMapping("/owners/{ownerId}/signals")
    @Operation(summary = "Get deterministic explainable attention signals for an owner")
    public List<DecisionSignal> signals(@PathVariable UUID ownerId) { return portfolioService.signals(ownerId); }

    @GetMapping("/deals/{dealId}/evidence/preview")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @Operation(summary = "Preview the evidence snapshot that would be frozen for a deal")
    public EvidencePreview evidencePreview(@PathVariable UUID dealId) { return portfolioService.evidencePreview(dealId); }

    @PostMapping("/deals/{dealId}/evidence")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Confirm and immutably freeze current decision evidence for a deal")
    public EvidenceSnapshot confirmEvidence(@PathVariable UUID dealId) { return portfolioService.confirmEvidence(dealId); }

    @GetMapping("/deals/{dealId}/evidence")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @Operation(summary = "List confirmed evidence snapshots for a deal")
    public List<EvidenceSnapshot> evidence(@PathVariable UUID dealId) { return portfolioService.listEvidence(dealId); }

    @GetMapping("/evidence/{snapshotId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @Operation(summary = "Open one immutable evidence snapshot")
    public EvidenceSnapshot evidenceSnapshot(@PathVariable UUID snapshotId) { return portfolioService.getEvidence(snapshotId); }

    @GetMapping("/workbaskets")
    @Operation(summary = "List current user's saved map workbaskets")
    public List<Workbasket> workbaskets() { return workbasketService.list(); }

    @PostMapping("/workbaskets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save an access-checked map selection as a reusable workbasket")
    public Workbasket createWorkbasket(@Valid @RequestBody SaveWorkbasketRequest request) { return workbasketService.create(request); }

    @GetMapping("/workbaskets/{id}")
    @Operation(summary = "Open a saved workbasket and re-check current object access")
    public Workbasket workbasket(@PathVariable UUID id) { return workbasketService.get(id); }

    @PutMapping("/workbaskets/{id}")
    @Operation(summary = "Update a saved workbasket after validating its current selection")
    public Workbasket updateWorkbasket(@PathVariable UUID id, @Valid @RequestBody SaveWorkbasketRequest request) {
        return workbasketService.update(id, request);
    }

    @PostMapping("/workbaskets/{id}/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Hand a workbasket to an authorized user or role with purpose, due date and permission")
    public WorkbasketHandoff shareWorkbasket(@PathVariable UUID id, @Valid @RequestBody ShareWorkbasketRequest request) {
        return handoffService.share(id, request);
    }

    @GetMapping("/workbasket-handoffs/inbox")
    @Operation(summary = "List workbasket handoffs assigned to the current user or one of their roles")
    public List<WorkbasketHandoff> handoffInbox() { return handoffService.inbox(); }

    @GetMapping("/workbasket-handoffs/sent")
    @Operation(summary = "List workbasket handoffs created by the current user")
    public List<WorkbasketHandoff> handoffSent() { return handoffService.sent(); }

    @GetMapping("/workbasket-handoffs/{id}")
    @Operation(summary = "Open a handoff and re-apply current resource access before returning workbasket contents")
    public WorkbasketHandoffView handoff(@PathVariable UUID id) { return handoffService.open(id); }

    @PostMapping("/workbasket-handoffs/{id}/accept")
    @Operation(summary = "Accept a workbasket handoff")
    public WorkbasketHandoff acceptHandoff(@PathVariable UUID id) { return handoffService.accept(id); }

    @PostMapping("/workbasket-handoffs/{id}/cancel")
    @Operation(summary = "Cancel a workbasket handoff as its creator")
    public WorkbasketHandoff cancelHandoff(@PathVariable UUID id) { return handoffService.cancel(id); }

    @PutMapping("/workbasket-handoffs/{id}/workbasket")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Edit an accepted shared workbasket when the handoff grants EDIT permission")
    public void editSharedWorkbasket(@PathVariable UUID id, @Valid @RequestBody SaveWorkbasketRequest request) {
        sharedEditService.update(id, request);
    }
}
