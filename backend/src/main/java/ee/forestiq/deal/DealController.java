package ee.forestiq.deal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.deal.DealService.CallBrief;
import ee.forestiq.deal.DealService.DealDetails;
import ee.forestiq.deal.DealService.EvaluationTask;
import ee.forestiq.deal.DealLifecycleService.AssignEvaluatorCommand;
import ee.forestiq.deal.DealLifecycleService.CreateDealCommand;
import ee.forestiq.deal.DealLifecycleService.SubmitEvaluationCommand;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/deals")
public class DealController {
    private static final Set<OrganizationRole> EVALUATOR_ROLES = Set.of(
        OrganizationRole.EVALUATOR, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);

    private final DealService dealService;
    private final DealLifecycleService lifecycleService;
    private final OrganizationIdentityDirectory identityDirectory;
    private final OrganizationContext organizationContext;

    public DealController(DealService dealService, DealLifecycleService lifecycleService,
                          OrganizationIdentityDirectory identityDirectory,
                          OrganizationContext organizationContext) {
        this.dealService = dealService;
        this.lifecycleService = lifecycleService;
        this.identityDirectory = identityDirectory;
        this.organizationContext = organizationContext;
    }

    @GetMapping("/owners/{ownerId}/brief")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public CallBrief brief(@PathVariable UUID ownerId) { return dealService.brief(ownerId); }

    @GetMapping("/owners/{ownerId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public List<DealDetails> listByOwner(@PathVariable UUID ownerId) { return dealService.listByOwner(ownerId); }

    @PostMapping("/owners/{ownerId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
    public DealDetails create(@PathVariable UUID ownerId, @Valid @RequestBody CreateDealRequest request) {
        validateEvaluator(request.evaluatorSubject());
        return lifecycleService.create(ownerId, request.toLifecycleCommand());
    }

    @GetMapping("/evaluation-queue")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public List<EvaluationTask> evaluationQueue() { return dealService.evaluationQueue(); }

    @PostMapping("/{dealId}/evaluation-assignment/claim")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public DealDetails claimEvaluation(@PathVariable UUID dealId) { return lifecycleService.claimEvaluation(dealId); }

    @DeleteMapping("/{dealId}/evaluation-assignment/claim")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public DealDetails unclaimEvaluation(@PathVariable UUID dealId) { return lifecycleService.unclaimEvaluation(dealId); }

    @PutMapping("/{dealId}/evaluation-assignment")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public DealDetails assignEvaluator(@PathVariable UUID dealId, @Valid @RequestBody AssignEvaluatorRequest request) {
        validateEvaluator(request.evaluatorSubject());
        return lifecycleService.assignEvaluator(dealId, new AssignEvaluatorCommand(request.evaluatorSubject()));
    }

    @PostMapping("/{dealId}/evaluations")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public DealDetails submitEvaluation(@PathVariable UUID dealId, @Valid @RequestBody SubmitEvaluationRequest request) {
        return lifecycleService.submitEvaluation(dealId, request.toLifecycleCommand());
    }

    private void validateEvaluator(String evaluatorSubject) {
        if (evaluatorSubject == null || evaluatorSubject.isBlank()) return;
        identityDirectory.requireAssignableUser(organizationContext.requiredOrganizationId(), evaluatorSubject, EVALUATOR_ROLES);
    }

    public record CreateDealRequest(
        @NotNull @Pattern(regexp = "FOREST|LAND|BOTH") String saleSubject,
        @NotEmpty List<@NotNull UUID> parcelIds,
        @Size(max = 200) String decisionMaker,
        @Size(max = 200) String saleTimeframe,
        BigDecimal priceExpectation,
        @Size(max = 4000) String qualificationNotes,
        Boolean requestEvaluation,
        @Size(max = 200) String evaluatorSubject
    ) {
        CreateDealCommand toLifecycleCommand() {
            return new CreateDealCommand(saleSubject, parcelIds, decisionMaker, saleTimeframe,
                priceExpectation, qualificationNotes, requestEvaluation, evaluatorSubject);
        }
    }

    public record AssignEvaluatorRequest(@Size(max = 200) String evaluatorSubject) { }

    public record SubmitEvaluationRequest(
        @NotNull @Pattern(regexp = "NEEDS_DATA|SUBMITTED|APPROVED") String status,
        BigDecimal estimatedMinPrice,
        BigDecimal estimatedMaxPrice,
        BigDecimal recommendedPurchasePrice,
        BigDecimal internalMinPrice,
        BigDecimal proposedOfferPrice,
        LocalDate offerValidUntil,
        @Size(max = 4000) String assumptions,
        @Size(max = 4000) String risks,
        @Size(max = 2000) String returnedReason
    ) {
        SubmitEvaluationCommand toLifecycleCommand() {
            return new SubmitEvaluationCommand(status, estimatedMinPrice, estimatedMaxPrice,
                recommendedPurchasePrice, internalMinPrice, proposedOfferPrice, offerValidUntil,
                assumptions, risks, returnedReason);
        }
    }
}
