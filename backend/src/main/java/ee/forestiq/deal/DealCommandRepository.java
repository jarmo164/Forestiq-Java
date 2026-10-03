package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static ee.forestiq.jooq.Tables.DEAL_CASE_EVENT;
import static ee.forestiq.jooq.Tables.DEAL_CASE_PARCEL;
import static ee.forestiq.jooq.Tables.DEAL_EVALUATION;
import static ee.forestiq.jooq.Tables.REMINDER;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.DealService.CreateDealRequest;
import ee.forestiq.deal.DealService.SubmitEvaluationRequest;

@Repository
class DealCommandRepository {

    private final DSLContext dsl;

    DealCommandRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void createDeal(UUID dealId, UUID orgId, UUID ownerId, CreateDealRequest request, String stage,
                    String salesSubject, String evaluatorSubject, String nextAction,
                    Instant nextActionDueAt, Instant evaluationDueAt, List<UUID> parcelIds) {
        Instant now = Instant.now();
        dsl.insertInto(DEAL_CASE)
            .columns(DEAL_CASE.ID, DEAL_CASE.ORGANIZATION_ID, DEAL_CASE.OWNER_ID, DEAL_CASE.SALE_SUBJECT, DEAL_CASE.STAGE,
                DEAL_CASE.SALES_SUBJECT, DEAL_CASE.EVALUATOR_SUBJECT, DEAL_CASE.DECISION_MAKER, DEAL_CASE.SALE_TIMEFRAME,
                DEAL_CASE.PRICE_EXPECTATION, DEAL_CASE.QUALIFICATION_NOTES, DEAL_CASE.NEXT_ACTION, DEAL_CASE.NEXT_ACTION_DUE_AT,
                DEAL_CASE.EVALUATION_DUE_AT, DEAL_CASE.STAGE_SET_AT, DEAL_CASE.CREATED_BY_SUBJECT)
            .values(dealId, orgId, ownerId, request.saleSubject(), stage, salesSubject, evaluatorSubject,
                blankToNull(request.decisionMaker()), blankToNull(request.saleTimeframe()), request.priceExpectation(),
                blankToNull(request.qualificationNotes()), nextAction, dbTime(nextActionDueAt), dbTime(evaluationDueAt), dbTime(now), salesSubject)
            .execute();
        for (UUID parcelId : parcelIds) {
            dsl.insertInto(DEAL_CASE_PARCEL)
                .columns(DEAL_CASE_PARCEL.DEAL_CASE_ID, DEAL_CASE_PARCEL.ORGANIZATION_ID, DEAL_CASE_PARCEL.PARCEL_ID)
                .values(dealId, orgId, parcelId)
                .execute();
        }
    }

    int claimEvaluation(UUID orgId, UUID dealId, String expectedStage, String subject, boolean organizationWide) {
        Condition where = DEAL_CASE.ID.eq(dealId)
            .and(DEAL_CASE.ORGANIZATION_ID.eq(orgId))
            .and(DEAL_CASE.STAGE.eq(expectedStage));
        if (!organizationWide) where = where.and(DEAL_CASE.EVALUATOR_SUBJECT.isNull().or(DEAL_CASE.EVALUATOR_SUBJECT.eq(subject)));
        OffsetDateTime now = dbTime(Instant.now());
        return dsl.update(DEAL_CASE)
            .set(DEAL_CASE.EVALUATOR_SUBJECT, subject)
            .set(DEAL_CASE.STAGE, "EVALUATING")
            .set(DEAL_CASE.STAGE_SET_AT, now)
            .set(DEAL_CASE.UPDATED_AT, now)
            .where(where)
            .execute();
    }

    int unclaimEvaluation(UUID orgId, UUID dealId, String expectedStage, String currentEvaluator) {
        OffsetDateTime now = dbTime(Instant.now());
        return dsl.update(DEAL_CASE)
            .setNull(DEAL_CASE.EVALUATOR_SUBJECT)
            .set(DEAL_CASE.STAGE, "EVALUATION_REQUESTED")
            .set(DEAL_CASE.STAGE_SET_AT, now)
            .set(DEAL_CASE.UPDATED_AT, now)
            .where(DEAL_CASE.ID.eq(dealId)
                .and(DEAL_CASE.ORGANIZATION_ID.eq(orgId))
                .and(DEAL_CASE.STAGE.eq(expectedStage))
                .and(DEAL_CASE.EVALUATOR_SUBJECT.eq(currentEvaluator)))
            .execute();
    }

    int assignEvaluator(UUID orgId, UUID dealId, String expectedStage, String evaluatorSubject) {
        String targetStage = evaluatorSubject == null ? "EVALUATION_REQUESTED" : "EVALUATING";
        OffsetDateTime now = dbTime(Instant.now());
        return dsl.update(DEAL_CASE)
            .set(DEAL_CASE.EVALUATOR_SUBJECT, evaluatorSubject)
            .set(DEAL_CASE.STAGE, targetStage)
            .set(DEAL_CASE.STAGE_SET_AT, now)
            .set(DEAL_CASE.UPDATED_AT, now)
            .where(DEAL_CASE.ID.eq(dealId)
                .and(DEAL_CASE.ORGANIZATION_ID.eq(orgId))
                .and(DEAL_CASE.STAGE.eq(expectedStage)))
            .execute();
    }

    void lockDeal(UUID orgId, UUID dealId) {
        Record locked = dsl.select(DEAL_CASE.ID)
            .from(DEAL_CASE)
            .where(DEAL_CASE.ID.eq(dealId).and(DEAL_CASE.ORGANIZATION_ID.eq(orgId)))
            .forUpdate()
            .fetchOne();
        if (locked == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tehingujuhtumit ei leitud.");
    }

    int nextEvaluationRevision(UUID orgId, UUID dealId) {
        Integer revision = dsl.select(coalesce(max(DEAL_EVALUATION.REVISION), 0).plus(1))
            .from(DEAL_EVALUATION)
            .where(DEAL_EVALUATION.DEAL_CASE_ID.eq(dealId).and(DEAL_EVALUATION.ORGANIZATION_ID.eq(orgId)))
            .fetchOne(0, Integer.class);
        return revision == null ? 1 : revision;
    }

    void insertEvaluation(UUID orgId, UUID dealId, int revision, SubmitEvaluationRequest request, String subject) {
        dsl.insertInto(DEAL_EVALUATION)
            .columns(DEAL_EVALUATION.ID, DEAL_EVALUATION.DEAL_CASE_ID, DEAL_EVALUATION.ORGANIZATION_ID, DEAL_EVALUATION.REVISION,
                DEAL_EVALUATION.STATUS, DEAL_EVALUATION.ESTIMATED_MIN_PRICE, DEAL_EVALUATION.ESTIMATED_MAX_PRICE,
                DEAL_EVALUATION.RECOMMENDED_PURCHASE_PRICE, DEAL_EVALUATION.INTERNAL_MIN_PRICE, DEAL_EVALUATION.PROPOSED_OFFER_PRICE,
                DEAL_EVALUATION.OFFER_VALID_UNTIL, DEAL_EVALUATION.ASSUMPTIONS, DEAL_EVALUATION.RISKS, DEAL_EVALUATION.RETURNED_REASON,
                DEAL_EVALUATION.CREATED_BY_SUBJECT, DEAL_EVALUATION.SUBMITTED_AT)
            .values(UUID.randomUUID(), dealId, orgId, revision, request.status(), request.estimatedMinPrice(), request.estimatedMaxPrice(),
                request.recommendedPurchasePrice(), request.internalMinPrice(), request.proposedOfferPrice(), request.offerValidUntil(),
                blankToNull(request.assumptions()), blankToNull(request.risks()), blankToNull(request.returnedReason()), subject, dbTime(Instant.now()))
            .execute();
    }

    int completeEvaluation(UUID orgId, UUID dealId, String expectedStage, String targetStage, String nextAction, Instant dueAt) {
        OffsetDateTime now = dbTime(Instant.now());
        return dsl.update(DEAL_CASE)
            .set(DEAL_CASE.STAGE, targetStage)
            .set(DEAL_CASE.STAGE_SET_AT, now)
            .set(DEAL_CASE.NEXT_ACTION, nextAction)
            .set(DEAL_CASE.NEXT_ACTION_DUE_AT, dbTime(dueAt))
            .set(DEAL_CASE.UPDATED_AT, now)
            .where(DEAL_CASE.ID.eq(dealId)
                .and(DEAL_CASE.ORGANIZATION_ID.eq(orgId))
                .and(DEAL_CASE.STAGE.eq(expectedStage)))
            .execute();
    }

    int startNegotiation(UUID orgId, UUID dealId, String expectedStage, String nextAction, Instant dueAt) {
        OffsetDateTime now = dbTime(Instant.now());
        return dsl.update(DEAL_CASE)
            .set(DEAL_CASE.STAGE, "NEGOTIATION")
            .set(DEAL_CASE.STAGE_SET_AT, now)
            .set(DEAL_CASE.NEXT_ACTION, nextAction)
            .set(DEAL_CASE.NEXT_ACTION_DUE_AT, dbTime(dueAt))
            .set(DEAL_CASE.UPDATED_AT, now)
            .where(DEAL_CASE.ID.eq(dealId)
                .and(DEAL_CASE.ORGANIZATION_ID.eq(orgId))
                .and(DEAL_CASE.STAGE.eq(expectedStage)))
            .execute();
    }

    void addReminder(UUID orgId, String creator, UUID ownerId, String text, Instant dueAt) {
        dsl.insertInto(REMINDER)
            .columns(REMINDER.ORGANIZATION_ID, REMINDER.CREATOR, REMINDER.OWNER_ID, REMINDER.REMINDER_TEXT, REMINDER.DUE_TIME)
            .values(orgId, creator, ownerId, text, dbTime(dueAt))
            .execute();
    }

    void addEvent(UUID orgId, UUID dealId, String type, String description, Map<String, String> data, String subject) {
        dsl.insertInto(DEAL_CASE_EVENT)
            .columns(DEAL_CASE_EVENT.ID, DEAL_CASE_EVENT.ORGANIZATION_ID, DEAL_CASE_EVENT.DEAL_CASE_ID, DEAL_CASE_EVENT.EVENT_TYPE,
                DEAL_CASE_EVENT.DESCRIPTION, DEAL_CASE_EVENT.EVENT_DATA, DEAL_CASE_EVENT.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), orgId, dealId, type, description, JSONB.valueOf(json(data)), subject)
            .execute();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String json(Map<String, String> data) {
        return "{" + data.entrySet().stream()
            .map(entry -> "\"" + entry.getKey().replace("\"", "") + "\":\"" + entry.getValue().replace("\"", "") + "\"")
            .collect(java.util.stream.Collectors.joining(",")) + "}";
    }
}
