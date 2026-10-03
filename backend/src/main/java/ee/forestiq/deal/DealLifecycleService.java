package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static ee.forestiq.jooq.Tables.DEAL_CASE_EVENT;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.DealCommercialService.CancelDealRequest;
import ee.forestiq.deal.DealCommercialService.CommercialState;
import ee.forestiq.deal.DealCommercialService.CounterOfferRequest;
import ee.forestiq.deal.DealCommercialService.PrepareOfferRequest;
import ee.forestiq.deal.DealCommercialService.WinDealRequest;
import ee.forestiq.deal.DealService.DealDetails;
import ee.forestiq.deal.DealWorkDtos.LossOutcome;
import ee.forestiq.deal.DealWorkDtos.LossReasonDefinition;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;

/**
 * Typed application command surface for every mutation of a deal lifecycle.
 *
 * <p>HTTP adapters map their public request DTOs to these commands. Read-side work projection
 * and commercial state remain separate services, while lifecycle side effects commit atomically
 * through this boundary.</p>
 */
@Service
public class DealLifecycleService {

    private static final Set<String> TERMINAL_STAGES = Set.of("WON", "LOST", "CANCELLED");

    private final DealService dealService;
    private final DealCommercialService commercialService;
    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final OwnerCollaborationPort ownerCollaboration;

    public DealLifecycleService(
        DealService dealService,
        DealCommercialService commercialService,
        DSLContext dsl,
        OrganizationContext organizationContext,
        OwnerCollaborationPort ownerCollaboration) {
        this.dealService = dealService;
        this.commercialService = commercialService;
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.ownerCollaboration = ownerCollaboration;
    }

    @Transactional
    public DealDetails create(UUID ownerId, CreateDealCommand command) {
        return dealService.create(ownerId, new DealService.CreateDealRequest(
            command.saleSubject(), command.parcelIds(), command.decisionMaker(), command.saleTimeframe(),
            command.priceExpectation(), command.qualificationNotes(), command.requestEvaluation(), command.evaluatorSubject()));
    }

    @Transactional
    public DealDetails claimEvaluation(UUID dealId) {
        return dealService.claimEvaluation(dealId);
    }

    @Transactional
    public DealDetails unclaimEvaluation(UUID dealId) {
        return dealService.unclaimEvaluation(dealId);
    }

    @Transactional
    public DealDetails assignEvaluator(UUID dealId, AssignEvaluatorCommand command) {
        return dealService.assignEvaluator(dealId, new DealService.AssignEvaluatorRequest(command.evaluatorSubject()));
    }

    @Transactional
    public DealDetails submitEvaluation(UUID dealId, SubmitEvaluationCommand command) {
        return dealService.submitEvaluation(dealId, new DealService.SubmitEvaluationRequest(
            command.status(), command.estimatedMinPrice(), command.estimatedMaxPrice(),
            command.recommendedPurchasePrice(), command.internalMinPrice(), command.proposedOfferPrice(),
            command.offerValidUntil(), command.assumptions(), command.risks(), command.returnedReason()));
    }

    @Transactional
    public CommercialState prepareOffer(UUID dealId, PrepareOfferCommand command) {
        return commercialService.prepareOffer(dealId,
            new PrepareOfferRequest(command.amount(), command.validUntil(), command.terms()));
    }

    @Transactional
    public CommercialState sendOffer(UUID dealId) {
        return commercialService.sendOffer(dealId);
    }

    @Transactional
    public CommercialState recordCounterOffer(UUID dealId, CounterOfferCommand command) {
        return commercialService.recordCounterOffer(dealId, new CounterOfferRequest(command.amount(), command.note()));
    }

    @Transactional
    public CommercialState win(UUID dealId, WinDealCommand command) {
        return commercialService.win(dealId, new WinDealRequest(command.acceptedEntryId(), command.note()));
    }

    @Transactional
    public CommercialState cancel(UUID dealId, CancelDealCommand command) {
        return commercialService.cancel(dealId, new CancelDealRequest(command.note()));
    }

    @Transactional
    public void updateNextAction(UUID dealId, UpdateNextActionCommand command) {
        if (command == null || command.nextAction() == null || command.nextAction().isBlank()
            || command.dueAt() == null || !command.dueAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Järgmise tegevuse tähtaeg peab olema tulevikus.");
        }

        UUID organizationId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeMutation(organizationId, dealId);
        String stage = deal.get(DEAL_CASE.STAGE);
        if (TERMINAL_STAGES.contains(stage)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Lõpetatud tehingu järgmist tegevust ei saa muuta.");
        }

        int updated = dsl.update(DEAL_CASE)
            .set(DEAL_CASE.NEXT_ACTION, command.nextAction().trim())
            .set(DEAL_CASE.NEXT_ACTION_DUE_AT, dbTime(command.dueAt()))
            .set(DEAL_CASE.UPDATED_AT, dbTime(Instant.now()))
            .where(DEAL_CASE.ORGANIZATION_ID.eq(organizationId)
                .and(DEAL_CASE.ID.eq(dealId))
                .and(DEAL_CASE.STAGE.eq(stage))
                .and(DEAL_CASE.CLOSED_AT.isNull()))
            .execute();
        assertUpdated(updated);
        appendEvent(organizationId, dealId, "NEXT_ACTION_UPDATED",
            "Järgmine tegevus muudeti: " + command.nextAction().trim(),
            "{\"dueAt\":\"" + command.dueAt() + "\"}");
    }

    /** Canonical loss command used by both the commercial and deal-work HTTP adapters. */
    @Transactional
    public LossOutcome lose(UUID dealId, LoseDealCommand command) {
        if (command == null || blank(command.reasonCode()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kaotuse põhjus on kohustuslik.");
        }
        if (command.followUpAt() != null && !command.followUpAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Järelkontakti aeg peab olema tulevikus.");
        }

        UUID organizationId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeMutation(organizationId, dealId);
        UUID ownerId = deal.get(DEAL_CASE.OWNER_ID);
        String previousStage = deal.get(DEAL_CASE.STAGE);
        String salesSubject = deal.get(DEAL_CASE.SALES_SUBJECT);
        if (TERMINAL_STAGES.contains(previousStage) || deal.get(DEAL_CASE.CLOSED_AT) != null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Tehing on juba lõpetatud.");
        }

        Record reason = dsl.fetchOne("""
            SELECT code, label FROM deal_loss_reason_definition
             WHERE organization_id = ? AND code = ? AND active = TRUE
            """, organizationId, command.reasonCode());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valitud kaotuse põhjus ei ole aktiivne.");
        }
        if (command.rejectedEntryId() != null && !offerEntryExists(organizationId, dealId, command.rejectedEntryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tagasilükatud pakkumist ei leitud selle tehingu ajaloost.");
        }

        String note = blank(command.note());
        String reasonLabel = reason.get("label", String.class);
        dsl.execute("""
            INSERT INTO deal_loss_outcome
              (organization_id, deal_case_id, reason_code, lost_from_stage, sales_subject,
               note, follow_up_at, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, organizationId, dealId, command.reasonCode(), previousStage, salesSubject,
            note, command.followUpAt(), organizationContext.subject());

        int updated = dsl.update(DEAL_CASE)
            .set(DEAL_CASE.STAGE, "LOST")
            .set(DEAL_CASE.STAGE_SET_AT, dbTime(Instant.now()))
            .set(DEAL_CASE.CLOSED_AT, dbTime(Instant.now()))
            .set(DEAL_CASE.NEXT_ACTION, "Tehing lõpetatud")
            .setNull(DEAL_CASE.NEXT_ACTION_DUE_AT)
            .set(DEAL_CASE.UPDATED_AT, dbTime(Instant.now()))
            .where(DEAL_CASE.ORGANIZATION_ID.eq(organizationId)
                .and(DEAL_CASE.ID.eq(dealId))
                .and(DEAL_CASE.STAGE.eq(previousStage))
                .and(DEAL_CASE.CLOSED_AT.isNull()))
            .execute();
        assertUpdated(updated);

        appendEvent(organizationId, dealId, "DEAL_LOST", "Tehing märgiti kaotatuks: " + reasonLabel + ".",
            "{\"lossReason\":\"" + json(command.reasonCode()) + "\","
                + "\"lossReasonLabel\":\"" + json(reasonLabel) + "\","
                + "\"lostFromStage\":\"" + json(previousStage) + "\","
                + "\"note\":\"" + json(note) + "\","
                + "\"followUpAt\":\"" + json(command.followUpAt() == null ? "" : command.followUpAt().toString()) + "\","
                + "\"entryId\":\"" + json(command.rejectedEntryId() == null ? "" : command.rejectedEntryId().toString()) + "\"}");

        if (command.followUpAt() != null) {
            Record reminder = dsl.fetchOne("""
                INSERT INTO reminder
                  (organization_id, creator, owner_id, reminder_text, due_time, assignee_subject, status,
                   activity_type, priority_reason, deal_case_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 'FOLLOW_UP', 'Kaotatud tehingu kokkulepitud järelkontakt', ?, now())
                RETURNING id
                """, organizationId, organizationContext.subject(), ownerId,
                "Võta kaotatud tehingu järel uuesti ühendust", command.followUpAt(), salesSubject, dealId);
            Long reminderId = reminder == null ? null : reminder.get("id", Long.class);
            if (reminderId == null) throw new IllegalStateException("Järelkontakti tööüksust ei loodud.");
            dsl.execute("""
                INSERT INTO reminder_event
                  (organization_id, reminder_id, owner_id, deal_case_id, event_type, new_due_time,
                   to_assignee, reason, created_by_subject)
                VALUES (?, ?, ?, ?, 'CREATED', ?, ?, 'Kaotatud tehingu järelkontakt', ?)
                """, organizationId, reminderId, ownerId, dealId, command.followUpAt(), salesSubject,
                organizationContext.subject());
        }

        ownerCollaboration.appendDealSystemActivity(ownerId,
            "Tehingujuhtum kaotati. Põhjus: " + reasonLabel + ".");
        return lossOutcome(organizationId, dealId);
    }

    @Transactional
    public LossReasonDefinition updateLossReason(String rawCode, UpdateLossReasonCommand command) {
        if (!ownerCollaboration.hasOrganizationWideAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Kaotuse põhjuseid saab hallata müügijuht või administraator.");
        }
        String code = blank(rawCode);
        if (code == null || !code.matches("[A-Z0-9_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vigane põhjuse kood.");
        }
        if (command == null || blank(command.label()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Põhjuse nimetus on kohustuslik.");
        }
        UUID organizationId = organizationContext.requiredOrganizationId();
        dsl.execute("""
            INSERT INTO deal_loss_reason_definition (organization_id, code, label, active, sort_order, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (organization_id, code) DO UPDATE
              SET label = EXCLUDED.label, active = EXCLUDED.active, sort_order = EXCLUDED.sort_order, updated_at = now()
            """, organizationId, code, command.label().trim(), command.active(), command.sortOrder());
        return new LossReasonDefinition(code, command.label().trim(), command.active(), command.sortOrder());
    }

    private Record lockAndAuthorizeMutation(UUID organizationId, UUID dealId) {
        Record deal = dsl.select(DEAL_CASE.OWNER_ID, DEAL_CASE.STAGE, DEAL_CASE.SALES_SUBJECT, DEAL_CASE.CLOSED_AT)
            .from(DEAL_CASE)
            .where(DEAL_CASE.ORGANIZATION_ID.eq(organizationId).and(DEAL_CASE.ID.eq(dealId)))
            .forUpdate()
            .fetchOne();
        if (deal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tehingujuhtumit ei leitud.");
        ownerCollaboration.requireMutable(deal.get(DEAL_CASE.OWNER_ID));
        if (!ownerCollaboration.hasOrganizationWideAccess()
            && !organizationContext.subject().equals(deal.get(DEAL_CASE.SALES_SUBJECT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tehingut saab muuta selle müügivastutaja.");
        }
        return deal;
    }

    private boolean offerEntryExists(UUID organizationId, UUID dealId, UUID entryId) {
        return dsl.fetchExists(dsl.selectOne()
            .from(DEAL_CASE_EVENT)
            .where(DEAL_CASE_EVENT.ORGANIZATION_ID.eq(organizationId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId))
                .and(DEAL_CASE_EVENT.EVENT_TYPE.in("OFFER_PREPARED", "CUSTOMER_COUNTEROFFER"))
                .and(org.jooq.impl.DSL.field("event_data->>'entryId'", String.class).eq(entryId.toString()))));
    }

    private LossOutcome lossOutcome(UUID organizationId, UUID dealId) {
        Record row = dsl.fetchOne("""
            SELECT o.deal_case_id, o.reason_code, d.label, o.lost_from_stage, o.sales_subject,
                   o.note, o.follow_up_at, o.created_by_subject, o.created_at
              FROM deal_loss_outcome o JOIN deal_loss_reason_definition d
                ON d.organization_id = o.organization_id AND d.code = o.reason_code
             WHERE o.organization_id = ? AND o.deal_case_id = ?
            """, organizationId, dealId);
        if (row == null) throw new IllegalStateException("Kaotuse tulemust ei loodud.");
        OffsetDateTime followUpAt = row.get("follow_up_at", OffsetDateTime.class);
        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        return new LossOutcome(dealId, row.get("reason_code", String.class), row.get("label", String.class),
            row.get("lost_from_stage", String.class), row.get("sales_subject", String.class),
            row.get("note", String.class), followUpAt == null ? null : followUpAt.toInstant(),
            row.get("created_by_subject", String.class), createdAt == null ? null : createdAt.toInstant());
    }

    private void appendEvent(UUID organizationId, UUID dealId, String eventType, String description, String eventData) {
        dsl.insertInto(DEAL_CASE_EVENT)
            .columns(DEAL_CASE_EVENT.ID, DEAL_CASE_EVENT.ORGANIZATION_ID, DEAL_CASE_EVENT.DEAL_CASE_ID,
                DEAL_CASE_EVENT.EVENT_TYPE, DEAL_CASE_EVENT.DESCRIPTION, DEAL_CASE_EVENT.EVENT_DATA,
                DEAL_CASE_EVENT.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), organizationId, dealId, eventType, description,
                org.jooq.JSONB.valueOf(eventData), organizationContext.subject())
            .execute();
    }

    private static void assertUpdated(int updated) {
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tehingujuhtumit muudeti samal ajal. Värskenda andmed ja proovi uuesti.");
        }
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record CreateDealCommand(String saleSubject, List<UUID> parcelIds, String decisionMaker,
                                    String saleTimeframe, BigDecimal priceExpectation, String qualificationNotes,
                                    Boolean requestEvaluation, String evaluatorSubject) {
    }

    public record AssignEvaluatorCommand(String evaluatorSubject) {
    }

    public record SubmitEvaluationCommand(String status, BigDecimal estimatedMinPrice, BigDecimal estimatedMaxPrice,
                                          BigDecimal recommendedPurchasePrice, BigDecimal internalMinPrice,
                                          BigDecimal proposedOfferPrice, LocalDate offerValidUntil, String assumptions,
                                          String risks, String returnedReason) {
    }

    public record PrepareOfferCommand(BigDecimal amount, LocalDate validUntil, String terms) {
    }

    public record CounterOfferCommand(BigDecimal amount, String note) {
    }

    public record WinDealCommand(UUID acceptedEntryId, String note) {
    }

    public record CancelDealCommand(String note) {
    }

    public record UpdateNextActionCommand(String nextAction, Instant dueAt) {
    }

    public record LoseDealCommand(String reasonCode, UUID rejectedEntryId, String note, Instant followUpAt) {
    }

    public record UpdateLossReasonCommand(String label, boolean active, int sortOrder) {
    }
}
