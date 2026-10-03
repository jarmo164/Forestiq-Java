package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.time.BusinessCalendar;

@Service
public class DealService {
    private static final List<String> SALE_SUBJECTS = List.of("FOREST", "LAND", "BOTH");
    private static final List<String> ACTIVE_EVALUATION_STAGES = List.of("EVALUATION_REQUESTED", "EVALUATING");

    private final OrganizationContext organizationContext;
    private final OwnerCollaborationPort ownerCollaboration;
    private final DealQueryRepository queryRepository;
    private final DealCommandRepository commandRepository;
    private final BusinessCalendar businessCalendar;

    @Autowired
    public DealService(OrganizationContext organizationContext,
                       OwnerCollaborationPort ownerCollaboration,
                       DealQueryRepository queryRepository, DealCommandRepository commandRepository,
                       BusinessCalendar businessCalendar) {
        this.organizationContext = organizationContext;
        this.ownerCollaboration = ownerCollaboration;
        this.queryRepository = queryRepository;
        this.commandRepository = commandRepository;
        this.businessCalendar = businessCalendar;
    }

    /** Compatibility constructor retained for focused H2 characterization tests. */
    public DealService(DSLContext dsl, OrganizationContext organizationContext,
                       OwnerCollaborationPort ownerCollaboration,
                       DealQueryRepository queryRepository, BusinessCalendar businessCalendar) {
        this(organizationContext, ownerCollaboration, queryRepository,
            new DealCommandRepository(dsl), businessCalendar);
    }

    @Transactional(readOnly = true)
    public CallBrief brief(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        var owner = ownerCollaboration.requireReadable(ownerId);
        return new CallBrief(ownerId, owner.displayName(), owner.status(), owner.phone(),
            owner.email(), owner.address(), owner.assignedToSubject(), queryRepository.briefParcels(orgId, ownerId),
            queryRepository.lastActivityAt(orgId, ownerId), queryRepository.latestActivity(orgId, ownerId),
            queryRepository.nextReminderAt(orgId, ownerId, organizationContext.subject()), queryRepository.listByOwner(orgId, ownerId));
    }

    @Transactional
    public DealDetails create(UUID ownerId, CreateDealRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        ownerCollaboration.assertDealCreationAllowed(ownerId);
        validateCreate(request);

        List<UUID> parcelIds = request.parcelIds() == null ? List.of() : request.parcelIds().stream().distinct().toList();
        if (parcelIds.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vali vähemalt üks hinnatav kinnistu.");
        if (queryRepository.countOwnedParcels(orgId, ownerId, parcelIds) != parcelIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valitud kinnistu ei kuulu sellele kliendile.");
        }

        boolean requestEvaluation = request.requestEvaluation() == null || request.requestEvaluation();
        String evaluatorSubject = blankToNull(request.evaluatorSubject());
        if (evaluatorSubject != null && !ownerCollaboration.hasOrganizationWideAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Hindaja saab määrata ainult müügijuht või administraator.");
        }

        String stage = requestEvaluation
            ? (evaluatorSubject == null ? "EVALUATION_REQUESTED" : "EVALUATING")
            : "QUALIFIED";
        Instant now = Instant.now();
        Instant evaluationDueAt = requestEvaluation ? businessCalendar.addBusinessDays(now, 3) : null;
        Instant nextActionDueAt = requestEvaluation ? null : businessCalendar.addBusinessDays(now, 1);
        UUID dealId = UUID.randomUUID();
        String nextAction = requestEvaluation
            ? "Hindaja koostab väärtusotsuse"
            : "Täpsusta kliendi müügieesmärk ja vali hindamise aeg";

        commandRepository.createDeal(dealId, orgId, ownerId, request, stage, subject, evaluatorSubject,
            nextAction, nextActionDueAt, evaluationDueAt, parcelIds);
        commandRepository.addEvent(orgId, dealId, "QUALIFIED",
            "Kliendi huvi kvalifitseeriti tehingujuhtumiks.", Map.of("saleSubject", request.saleSubject()), subject);
        ownerCollaboration.appendDealSystemActivity(ownerId, "Loodi tehingujuhtum: " + nextAction + ".");
        ownerCollaboration.applyDealLifecycle(ownerId, DealLifecycleTransition.QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION);
        if (requestEvaluation) {
            ownerCollaboration.applyDealLifecycle(ownerId, DealLifecycleTransition.REQUEST_EVALUATION);
            commandRepository.addEvent(orgId, dealId, "EVALUATION_REQUESTED",
                "Hindamisülesanne loodi tähtajaga " + evaluationDueAt + ".", Map.of(), subject);
            if (evaluatorSubject != null) {
                commandRepository.addEvent(orgId, dealId, "EVALUATOR_ASSIGNED",
                    "Hindamisülesanne määrati hindajale.", Map.of("evaluatorSubject", evaluatorSubject), subject);
            }
        }
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional(readOnly = true)
    public List<DealDetails> listByOwner(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        ownerCollaboration.requireReadable(ownerId);
        return queryRepository.listByOwner(orgId, ownerId);
    }

    @Transactional(readOnly = true)
    public List<EvaluationTask> evaluationQueue() {
        UUID orgId = organizationContext.requiredOrganizationId();
        Optional<String> evaluatorRestriction = ownerCollaboration.hasOrganizationWideAccess()
            ? Optional.empty()
            : Optional.of(organizationContext.subject());
        return queryRepository.evaluationQueue(orgId, ownerCollaboration.assignedSubjectRestriction(), evaluatorRestriction);
    }

    @Transactional
    public DealDetails claimEvaluation(UUID dealId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        authorizeDealOwnerMutation(deal);
        String expectedStage = activeEvaluationStage(deal);
        String currentEvaluator = deal.get(DEAL_CASE.EVALUATOR_SUBJECT);
        boolean organizationWide = ownerCollaboration.hasOrganizationWideAccess();
        if (currentEvaluator != null && !subject.equals(currentEvaluator) && !organizationWide) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Hindamisülesanne on määratud teisele hindajale.");
        }

        assertTransitionUpdated(commandRepository.claimEvaluation(orgId, dealId, expectedStage, subject, organizationWide));
        commandRepository.addEvent(orgId, dealId, "EVALUATION_CLAIMED",
            "Hindaja võttis hindamisülesande enda tööjärjekorda.", Map.of("evaluatorSubject", subject), subject);
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public DealDetails unclaimEvaluation(UUID dealId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        authorizeDealOwnerMutation(deal);
        String expectedStage = activeEvaluationStage(deal);
        String currentEvaluator = deal.get(DEAL_CASE.EVALUATOR_SUBJECT);
        boolean organizationWide = ownerCollaboration.hasOrganizationWideAccess();
        if (currentEvaluator == null) return queryRepository.details(deal);
        if (!subject.equals(currentEvaluator) && !organizationWide) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Ainult määratud hindaja või müügijuht saab ülesande vabastada.");
        }

        assertTransitionUpdated(commandRepository.unclaimEvaluation(orgId, dealId, expectedStage, currentEvaluator));
        commandRepository.addEvent(orgId, dealId, "EVALUATION_UNCLAIMED",
            "Hindamisülesanne vabastati hindajate ühisesse järjekorda.", Map.of("previousEvaluator", currentEvaluator), subject);
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public DealDetails assignEvaluator(UUID dealId, AssignEvaluatorRequest request) {
        if (!ownerCollaboration.hasOrganizationWideAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Hindamisülesandeid saab ümber määrata ainult müügijuht või administraator.");
        }
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        authorizeDealOwnerMutation(deal);
        String expectedStage = activeEvaluationStage(deal);
        String evaluatorSubject = request == null ? null : blankToNull(request.evaluatorSubject());

        assertTransitionUpdated(commandRepository.assignEvaluator(orgId, dealId, expectedStage, evaluatorSubject));
        commandRepository.addEvent(orgId, dealId,
            evaluatorSubject == null ? "EVALUATOR_UNASSIGNED" : "EVALUATOR_ASSIGNED",
            evaluatorSubject == null ? "Hindamisülesanne vabastati ühisesse järjekorda." : "Hindamisülesanne määrati hindajale.",
            Map.of("evaluatorSubject", evaluatorSubject == null ? "" : evaluatorSubject), subject);
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public DealDetails submitEvaluation(UUID dealId, SubmitEvaluationRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        UUID ownerId = authorizeDealOwnerMutation(deal);
        String expectedStage = activeEvaluationStage(deal);
        assertEvaluatorCanSubmit(deal, subject);
        validateEvaluation(request);

        commandRepository.lockDeal(orgId, dealId);
        int nextRevision = commandRepository.nextEvaluationRevision(orgId, dealId);
        commandRepository.insertEvaluation(orgId, dealId, nextRevision, request, subject);

        String stage = "NEEDS_DATA".equals(request.status()) ? "QUALIFIED" : "VALUED";
        String nextAction = "NEEDS_DATA".equals(request.status())
            ? "Täienda hindaja küsitud andmed"
            : "Võta kliendiga ühendust ja esita pakkumine";
        Instant dueAt = businessCalendar.addBusinessDays(Instant.now(), 1);
        assertTransitionUpdated(commandRepository.completeEvaluation(orgId, dealId, expectedStage, stage, nextAction, dueAt));

        commandRepository.addEvent(orgId, dealId, "EVALUATION_SUBMITTED",
            "Hindamisotsus versiooniga " + nextRevision + " esitati.", Map.of("status", request.status()), subject);
        commandRepository.addReminder(orgId, deal.get(DEAL_CASE.SALES_SUBJECT), ownerId, nextAction, dueAt);
        ownerCollaboration.appendDealSystemActivity(ownerId, "Hindamisotsus valmis: " + nextAction + ".");
        ownerCollaboration.applyDealLifecycle(ownerId,
            "NEEDS_DATA".equals(request.status())
                ? DealLifecycleTransition.COMPLETE_EVALUATION_NEEDS_DATA
                : DealLifecycleTransition.COMPLETE_EVALUATION_VALUED);
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    /**
     * Legacy service-level transition retained for compatibility tests. HTTP callers use DealCommercialService,
     * which requires a versioned offer before negotiation can progress.
     */
    @Transactional
    public DealDetails startNegotiation(UUID dealId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        UUID ownerId = authorizeDealOwnerMutation(deal);
        String expectedStage = deal.get(DEAL_CASE.STAGE);
        if (!"VALUED".equals(expectedStage)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Läbirääkimisi saab alustada pärast hindamisotsuse esitamist.");
        }
        if (!ownerCollaboration.hasOrganizationWideAccess() && !subject.equals(deal.get(DEAL_CASE.SALES_SUBJECT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Läbirääkimist saab alustada tehingu müügivastutaja.");
        }

        Instant now = Instant.now();
        String nextAction = "Esita pakkumine ja lepi kliendiga järgmine läbirääkimise samm";
        Instant dueAt = businessCalendar.addBusinessDays(now, 1);
        assertTransitionUpdated(commandRepository.startNegotiation(orgId, dealId, expectedStage, nextAction, dueAt));
        ownerCollaboration.applyDealLifecycle(ownerId, DealLifecycleTransition.START_NEGOTIATION);
        commandRepository.addEvent(orgId, dealId, "NEGOTIATION_STARTED",
            "Müügi vastutaja alustas läbirääkimisi pärast hindamisotsust.", Map.of(), subject);
        ownerCollaboration.appendDealSystemActivity(ownerId, "Tehingujuhtum liikus läbirääkimiste etappi.");
        return queryRepository.details(queryRepository.requireDeal(orgId, dealId));
    }

    private UUID authorizeDealOwnerMutation(Record deal) {
        UUID ownerId = deal.get(DEAL_CASE.OWNER_ID);
        ownerCollaboration.requireMutable(ownerId);
        return ownerId;
    }

    private String activeEvaluationStage(Record deal) {
        String stage = deal.get(DEAL_CASE.STAGE);
        if (!ACTIVE_EVALUATION_STAGES.contains(stage)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Toiming on lubatud ainult aktiivse hindamisülesande kohta.");
        }
        return stage;
    }

    private void assertEvaluatorCanSubmit(Record deal, String subject) {
        if (ownerCollaboration.hasOrganizationWideAccess()) return;
        String evaluatorSubject = deal.get(DEAL_CASE.EVALUATOR_SUBJECT);
        if (evaluatorSubject == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Võta hindamisülesanne enne otsuse esitamist enda tööjärjekorda.");
        }
        if (!subject.equals(evaluatorSubject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Hindamisotsuse saab esitada ainult ülesandele määratud hindaja.");
        }
    }

    private static void assertTransitionUpdated(int updated) {
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tehingujuhtumit muudeti samal ajal. Värskenda andmed ja proovi uuesti.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateCreate(CreateDealRequest request) {
        if (request == null || !SALE_SUBJECTS.contains(request.saleSubject())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Vali tehingu objekt: mets, maa või mõlemad.");
        }
    }

    private static void validateEvaluation(SubmitEvaluationRequest request) {
        if (request == null || !List.of("NEEDS_DATA", "SUBMITTED", "APPROVED").contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hindamisotsuse staatus puudub või on tundmatu.");
        }
        if (!"NEEDS_DATA".equals(request.status()) && request.recommendedPurchasePrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Soovituslik ostuhind on kohustuslik.");
        }
        if (request.estimatedMinPrice() != null && request.estimatedMaxPrice() != null
            && request.estimatedMinPrice().compareTo(request.estimatedMaxPrice()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hinnavahemiku alampiir ei tohi ületada ülempiiri.");
        }
    }

    public record BriefParcel(UUID id, String cadastralCode, BigDecimal areaHectares, String name,
                              String county, String municipality, Instant updatedAt) { }
    public record CallBrief(UUID ownerId, String ownerName, String ownerStatus, String phone, String email,
                            String address, String assignedTo, List<BriefParcel> parcels, Instant lastActivityAt,
                            String latestActivity, Instant nextReminderAt, List<DealDetails> deals) { }
    public record DealParcel(UUID id, String cadastralCode, BigDecimal areaHectares, String name) { }
    public record DealEvent(UUID id, String type, String description, Instant createdAt, String createdBy) { }
    public record DealEvaluation(UUID id, int revision, String status, BigDecimal estimatedMinPrice,
                                 BigDecimal estimatedMaxPrice, BigDecimal recommendedPurchasePrice,
                                 BigDecimal internalMinPrice, BigDecimal proposedOfferPrice, LocalDate offerValidUntil,
                                 String assumptions, String risks, String returnedReason, String createdBy, Instant submittedAt) { }
    public record DealDetails(UUID id, UUID ownerId, String saleSubject, String stage, String salesSubject,
                              String evaluatorSubject, String decisionMaker, String saleTimeframe,
                              BigDecimal priceExpectation, String qualificationNotes, String nextAction,
                              Instant nextActionDueAt, Instant evaluationDueAt, Instant createdAt,
                              List<DealParcel> parcels, DealEvaluation latestEvaluation, List<DealEvent> events) { }
    public record CreateDealRequest(String saleSubject, List<UUID> parcelIds, String decisionMaker, String saleTimeframe,
                                    BigDecimal priceExpectation, String qualificationNotes, Boolean requestEvaluation,
                                    String evaluatorSubject) { }
    public record SubmitEvaluationRequest(String status, BigDecimal estimatedMinPrice, BigDecimal estimatedMaxPrice,
                                          BigDecimal recommendedPurchasePrice, BigDecimal internalMinPrice,
                                          BigDecimal proposedOfferPrice, LocalDate offerValidUntil, String assumptions,
                                          String risks, String returnedReason) { }
    public record AssignEvaluatorRequest(String evaluatorSubject) { }
    public record EvaluationTask(UUID dealId, UUID ownerId, String ownerName, String saleSubject,
                                 String qualificationNotes, Instant evaluationDueAt, String salesSubject,
                                 String evaluatorSubject, String stage, int parcelCount) { }
}
