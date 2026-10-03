package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static ee.forestiq.jooq.Tables.DEAL_CASE_EVENT;
import static ee.forestiq.jooq.Tables.DEAL_CASE_PARCEL;
import static ee.forestiq.jooq.Tables.DEAL_EVALUATION;
import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.OWNER;
import static ee.forestiq.jooq.Tables.OWNER_ACTIVITY;
import static ee.forestiq.jooq.Tables.REMINDER;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.min;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.DealService.BriefParcel;
import ee.forestiq.deal.DealService.DealDetails;
import ee.forestiq.deal.DealService.DealEvaluation;
import ee.forestiq.deal.DealService.DealEvent;
import ee.forestiq.deal.DealService.DealParcel;
import ee.forestiq.deal.DealService.EvaluationTask;

@Repository
class DealQueryRepository {

    private final DSLContext dsl;

    DealQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    Record requireDeal(UUID orgId, UUID dealId) {
        Record deal = dsl.selectFrom(DEAL_CASE)
            .where(DEAL_CASE.ORGANIZATION_ID.eq(orgId).and(DEAL_CASE.ID.eq(dealId)))
            .fetchOne();
        if (deal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tehingujuhtumit ei leitud.");
        return deal;
    }

    int countOwnedParcels(UUID orgId, UUID ownerId, List<UUID> parcelIds) {
        return dsl.fetchCount(LAND_PARCEL, LAND_PARCEL.ORGANIZATION_ID.eq(orgId)
            .and(LAND_PARCEL.OWNER_ID.eq(ownerId))
            .and(LAND_PARCEL.ID.in(parcelIds)));
    }

    List<BriefParcel> briefParcels(UUID orgId, UUID ownerId) {
        return dsl.select(
                LAND_PARCEL.ID,
                LAND_PARCEL.CADASTRAL_CODE,
                LAND_PARCEL.AREA_HECTARES,
                LAND_PARCEL.NAME,
                LAND_PARCEL.COUNTY,
                LAND_PARCEL.MUNICIPALITY,
                LAND_PARCEL.UPDATED_AT)
            .from(LAND_PARCEL)
            .where(LAND_PARCEL.ORGANIZATION_ID.eq(orgId).and(LAND_PARCEL.OWNER_ID.eq(ownerId)))
            .orderBy(LAND_PARCEL.CADASTRAL_CODE.asc())
            .fetch(record -> new BriefParcel(
                record.value1(), record.value2(), record.value3(), record.value4(), record.value5(), record.value6(),
                toInstant(record.value7())));
    }

    Instant lastActivityAt(UUID orgId, UUID ownerId) {
        OffsetDateTime value = dsl.select(max(OWNER_ACTIVITY.CREATED_AT))
            .from(OWNER_ACTIVITY)
            .where(OWNER_ACTIVITY.ORGANIZATION_ID.eq(orgId).and(OWNER_ACTIVITY.OWNER_ID.eq(ownerId)))
            .fetchOne(0, OffsetDateTime.class);
        return toInstant(value);
    }

    Instant nextReminderAt(UUID orgId, UUID ownerId, String subject) {
        OffsetDateTime value = dsl.select(min(REMINDER.DUE_TIME))
            .from(REMINDER)
            .where(REMINDER.ORGANIZATION_ID.eq(orgId)
                .and(REMINDER.OWNER_ID.eq(ownerId))
                .and(REMINDER.CREATOR.eq(subject)))
            .fetchOne(0, OffsetDateTime.class);
        return toInstant(value);
    }

    String latestActivity(UUID orgId, UUID ownerId) {
        return dsl.select(OWNER_ACTIVITY.CONTENT)
            .from(OWNER_ACTIVITY)
            .where(OWNER_ACTIVITY.ORGANIZATION_ID.eq(orgId).and(OWNER_ACTIVITY.OWNER_ID.eq(ownerId)))
            .orderBy(OWNER_ACTIVITY.CREATED_AT.desc())
            .limit(1)
            .fetchOne(OWNER_ACTIVITY.CONTENT);
    }

    List<DealDetails> listByOwner(UUID orgId, UUID ownerId) {
        return dsl.selectFrom(DEAL_CASE)
            .where(DEAL_CASE.ORGANIZATION_ID.eq(orgId).and(DEAL_CASE.OWNER_ID.eq(ownerId)))
            .orderBy(DEAL_CASE.CREATED_AT.desc())
            .fetch(this::details);
    }

    List<EvaluationTask> evaluationQueue(UUID orgId, Optional<String> ownerAssignedSubjectRestriction,
                                         Optional<String> evaluatorSubjectRestriction) {
        var d = DEAL_CASE.as("d");
        var o = OWNER.as("o");
        var dp = DEAL_CASE_PARCEL.as("dp");

        Condition condition = d.ORGANIZATION_ID.eq(orgId)
            .and(d.STAGE.in("EVALUATION_REQUESTED", "EVALUATING"));
        if (ownerAssignedSubjectRestriction.isPresent()) {
            condition = condition.and(o.ASSIGNED_TO_SUBJECT.eq(ownerAssignedSubjectRestriction.get()));
        }
        if (evaluatorSubjectRestriction.isPresent()) {
            String evaluatorSubject = evaluatorSubjectRestriction.get();
            condition = condition.and(d.EVALUATOR_SUBJECT.isNull().or(d.EVALUATOR_SUBJECT.eq(evaluatorSubject)));
        }

        var parcelCount = count(dp.PARCEL_ID).as("parcel_count");
        return dsl.select(
                d.ID,
                d.OWNER_ID,
                o.DISPLAY_NAME,
                d.SALE_SUBJECT,
                d.QUALIFICATION_NOTES,
                d.EVALUATION_DUE_AT,
                d.SALES_SUBJECT,
                d.EVALUATOR_SUBJECT,
                d.STAGE,
                parcelCount)
            .from(d)
            .join(o).on(o.ID.eq(d.OWNER_ID).and(o.ORGANIZATION_ID.eq(d.ORGANIZATION_ID)))
            .leftJoin(dp).on(dp.DEAL_CASE_ID.eq(d.ID).and(dp.ORGANIZATION_ID.eq(d.ORGANIZATION_ID)))
            .where(condition)
            .groupBy(d.ID, d.OWNER_ID, o.DISPLAY_NAME, d.SALE_SUBJECT, d.QUALIFICATION_NOTES,
                d.EVALUATION_DUE_AT, d.SALES_SUBJECT, d.EVALUATOR_SUBJECT, d.STAGE)
            .orderBy(d.EVALUATION_DUE_AT.asc().nullsLast(), d.CREATED_AT.asc())
            .fetch(record -> new EvaluationTask(
                record.get(d.ID),
                record.get(d.OWNER_ID),
                record.get(o.DISPLAY_NAME),
                record.get(d.SALE_SUBJECT),
                record.get(d.QUALIFICATION_NOTES),
                toInstant(record.get(d.EVALUATION_DUE_AT)),
                record.get(d.SALES_SUBJECT),
                record.get(d.EVALUATOR_SUBJECT),
                record.get(d.STAGE),
                record.get(parcelCount)));
    }

    DealDetails details(Record row) {
        UUID dealId = row.get(DEAL_CASE.ID);
        UUID orgId = row.get(DEAL_CASE.ORGANIZATION_ID);
        var dp = DEAL_CASE_PARCEL.as("dp");
        var p = LAND_PARCEL.as("p");

        List<DealParcel> parcels = dsl.select(p.ID, p.CADASTRAL_CODE, p.AREA_HECTARES, p.NAME)
            .from(dp)
            .join(p).on(p.ID.eq(dp.PARCEL_ID).and(p.ORGANIZATION_ID.eq(dp.ORGANIZATION_ID)))
            .where(dp.ORGANIZATION_ID.eq(orgId).and(dp.DEAL_CASE_ID.eq(dealId)))
            .orderBy(p.CADASTRAL_CODE.asc())
            .fetch(record -> new DealParcel(record.value1(), record.value2(), record.value3(), record.value4()));

        Record evaluation = dsl.selectFrom(DEAL_EVALUATION)
            .where(DEAL_EVALUATION.ORGANIZATION_ID.eq(orgId)
                .and(DEAL_EVALUATION.DEAL_CASE_ID.eq(dealId)))
            .orderBy(DEAL_EVALUATION.REVISION.desc())
            .limit(1)
            .fetchOne();

        List<DealEvent> events = dsl.selectFrom(DEAL_CASE_EVENT)
            .where(DEAL_CASE_EVENT.ORGANIZATION_ID.eq(orgId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId)))
            .orderBy(DEAL_CASE_EVENT.CREATED_AT.desc())
            .fetch(record -> new DealEvent(
                record.get(DEAL_CASE_EVENT.ID),
                record.get(DEAL_CASE_EVENT.EVENT_TYPE),
                record.get(DEAL_CASE_EVENT.DESCRIPTION),
                toInstant(record.get(DEAL_CASE_EVENT.CREATED_AT)),
                record.get(DEAL_CASE_EVENT.CREATED_BY_SUBJECT)));

        return new DealDetails(
            dealId,
            row.get(DEAL_CASE.OWNER_ID),
            row.get(DEAL_CASE.SALE_SUBJECT),
            row.get(DEAL_CASE.STAGE),
            row.get(DEAL_CASE.SALES_SUBJECT),
            row.get(DEAL_CASE.EVALUATOR_SUBJECT),
            row.get(DEAL_CASE.DECISION_MAKER),
            row.get(DEAL_CASE.SALE_TIMEFRAME),
            row.get(DEAL_CASE.PRICE_EXPECTATION),
            row.get(DEAL_CASE.QUALIFICATION_NOTES),
            row.get(DEAL_CASE.NEXT_ACTION),
            toInstant(row.get(DEAL_CASE.NEXT_ACTION_DUE_AT)),
            toInstant(row.get(DEAL_CASE.EVALUATION_DUE_AT)),
            toInstant(row.get(DEAL_CASE.CREATED_AT)),
            parcels,
            evaluation == null ? null : mapEvaluation(evaluation),
            events);
    }

    private DealEvaluation mapEvaluation(Record row) {
        return new DealEvaluation(
            row.get(DEAL_EVALUATION.ID),
            row.get(DEAL_EVALUATION.REVISION),
            row.get(DEAL_EVALUATION.STATUS),
            row.get(DEAL_EVALUATION.ESTIMATED_MIN_PRICE),
            row.get(DEAL_EVALUATION.ESTIMATED_MAX_PRICE),
            row.get(DEAL_EVALUATION.RECOMMENDED_PURCHASE_PRICE),
            row.get(DEAL_EVALUATION.INTERNAL_MIN_PRICE),
            row.get(DEAL_EVALUATION.PROPOSED_OFFER_PRICE),
            row.get(DEAL_EVALUATION.OFFER_VALID_UNTIL),
            row.get(DEAL_EVALUATION.ASSUMPTIONS),
            row.get(DEAL_EVALUATION.RISKS),
            row.get(DEAL_EVALUATION.RETURNED_REASON),
            row.get(DEAL_EVALUATION.CREATED_BY_SUBJECT),
            toInstant(row.get(DEAL_EVALUATION.SUBMITTED_AT)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
