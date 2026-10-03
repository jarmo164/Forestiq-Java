package ee.forestiq.owner;

import static org.jooq.impl.DSL.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.*;

@Repository
public class OwnerQueryRepository {

    // --- owner ---
    private static final Table<Record> OWNER = table(name("owner")).as("o");
    private static final Field<UUID> O_ID = field(name("o", "id"), UUID.class);
    private static final Field<UUID> O_ORG = field(name("o", "organization_id"), UUID.class);
    private static final Field<String> O_NAME = field(name("o", "display_name"), String.class);
    private static final Field<String> O_CODE = field(name("o", "personal_or_registry_code"), String.class);
    private static final Field<String> O_STATUS = field(name("o", "status"), String.class);
    private static final Field<String> O_PHONE = field(name("o", "phone"), String.class);
    private static final Field<String> O_ASSIGNED = field(name("o", "assigned_to_subject"), String.class);
    private static final Field<Instant> O_UPDATED = field(name("o", "updated_at"), Instant.class);
    private static final Field<Instant> O_STATUS_SET = field(name("o", "status_set_at"), Instant.class);

    // --- land_parcel ---
    private static final Table<Record> PARCEL = table(name("land_parcel")).as("p");
    private static final Table<Record> OWNER_PARCEL_RELATION = table(name("owner_parcel_relation")).as("opr");
    private static final Field<UUID> OPR_OWNER = field(name("opr", "owner_id"), UUID.class);
    private static final Field<UUID> OPR_PARCEL = field(name("opr", "parcel_id"), UUID.class);
    private static final Field<UUID> OPR_ORG = field(name("opr", "organization_id"), UUID.class);
    private static final Field<Boolean> OPR_ACTIVE = field(name("opr", "active"), Boolean.class);
    private static final Field<UUID> P_ID = field(name("p", "id"), UUID.class);
    private static final Field<UUID> P_OWNER = field(name("p", "owner_id"), UUID.class);
    private static final Field<UUID> P_ORG = field(name("p", "organization_id"), UUID.class);
    private static final Field<String> P_CODE = field(name("p", "cadastral_code"), String.class);
    private static final Field<BigDecimal> P_AREA = field(name("p", "area_hectares"), BigDecimal.class);
    private static final Field<String> P_COUNTY = field(name("p", "county"), String.class);
    private static final Field<String> P_MUNICIPALITY = field(name("p", "municipality"), String.class);

    private final DSLContext dsl;

    public OwnerQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ===================== Owner search =====================

    public long countOwners(UUID orgId, String search, String assignedSubject) {
        return dsl.selectCount().from(OWNER).where(ownerCondition(orgId, search, assignedSubject)).fetchOne(0, long.class);
    }

    public List<OwnerListItem> findOwners(UUID orgId, String search, int page, int size, String assignedSubject) {
        Field<Integer> parcelCount = count(P_ID).as("parcel_count");
        return dsl.select(O_ID, O_NAME, O_CODE, O_STATUS, parcelCount, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .from(OWNER)
            .leftJoin(OWNER_PARCEL_RELATION).on(OPR_OWNER.eq(O_ID).and(OPR_ORG.eq(O_ORG)).and(OPR_ACTIVE.isTrue()))
            .leftJoin(PARCEL).on(P_ID.eq(OPR_PARCEL).and(P_ORG.eq(O_ORG)))
            .where(ownerCondition(orgId, search, assignedSubject))
            .groupBy(O_ID, O_NAME, O_CODE, O_STATUS, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .orderBy(O_UPDATED.desc(), O_ID.asc())
            .limit(size).offset(page * size)
            .fetch(r -> new OwnerListItem(
                r.get(O_ID), r.get(O_NAME), r.get(O_CODE), r.get(O_STATUS),
                r.get(parcelCount).longValue(), r.get(O_UPDATED), r.get(O_ASSIGNED), r.get(O_PHONE), r.get(O_STATUS_SET), List.of()));
    }

    /** Returns owners awaiting evaluation, ordered by the time they entered the workflow. */
    public List<OwnerListItem> findOwnersAwaitingEvaluation(UUID orgId) {
        Field<Integer> parcelCount = count(P_ID).as("parcel_count");
        return dsl.select(O_ID, O_NAME, O_CODE, O_STATUS, parcelCount, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .from(OWNER)
            .leftJoin(OWNER_PARCEL_RELATION).on(OPR_OWNER.eq(O_ID).and(OPR_ORG.eq(O_ORG)).and(OPR_ACTIVE.isTrue()))
            .leftJoin(PARCEL).on(P_ID.eq(OPR_PARCEL).and(P_ORG.eq(O_ORG)))
            .where(O_ORG.eq(orgId).and(O_STATUS.in(OwnerStatus.EVALUATION, OwnerStatus.WAITS_FOR_EVALUATION)))
            .groupBy(O_ID, O_NAME, O_CODE, O_STATUS, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .orderBy(O_STATUS_SET.asc().nullsLast(), O_ID.asc())
            .fetch(r -> new OwnerListItem(
                r.get(O_ID), r.get(O_NAME), r.get(O_CODE), r.get(O_STATUS),
                r.get(parcelCount).longValue(), r.get(O_UPDATED), r.get(O_ASSIGNED), r.get(O_PHONE), r.get(O_STATUS_SET), List.of()));
    }

    // ===================== Admin workdesk search =====================

    public List<OwnerListItem> adminSearch(UUID orgId, AdminSearchRequest req) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(O_ORG.eq(orgId));

        if (req.statuses() != null && !req.statuses().isEmpty())
            conditions.add(O_STATUS.in(req.statuses()));
        if (req.assignees() != null && !req.assignees().isEmpty())
            conditions.add(O_ASSIGNED.in(req.assignees()));
        if (req.mustHaveNoStatus() != null && req.mustHaveNoStatus())
            conditions.add(O_STATUS.isNull());
        if (req.mustHavePhone() != null && req.mustHavePhone())
            conditions.add(O_PHONE.isNotNull());
        if (req.mustNotHavePhone() != null && req.mustNotHavePhone())
            conditions.add(O_PHONE.isNull());
        if (req.ownerTypes() != null && !req.ownerTypes().isEmpty())
            conditions.add(field(name("o", "owner_type"), String.class).in(req.ownerTypes()));
        if (req.statusUpdatedSince() != null)
            conditions.add(O_STATUS_SET.greaterOrEqual(Instant.ofEpochMilli(req.statusUpdatedSince())));
        if (req.statusUpdatedTo() != null)
            conditions.add(O_STATUS_SET.lessOrEqual(Instant.ofEpochMilli(req.statusUpdatedTo())));
        if (Boolean.TRUE.equals(req.mustBeUnassigned()))
            conditions.add(O_ASSIGNED.isNull());

        // Cadastre-level filters via subquery
        if (hasParcelFilter(req)) {
            Condition parcelCond = P_ID.in(select(OPR_PARCEL).from(OWNER_PARCEL_RELATION)
                .where(OPR_OWNER.eq(O_ID).and(OPR_ORG.eq(O_ORG)).and(OPR_ACTIVE.isTrue()))).and(P_ORG.eq(O_ORG));
            if (req.counties() != null && !req.counties().isEmpty())
                parcelCond = parcelCond.and(P_COUNTY.in(req.counties()));
            if (req.municipalities() != null && !req.municipalities().isEmpty())
                parcelCond = parcelCond.and(P_MUNICIPALITY.in(req.municipalities()));
            if (req.areaMin() != null)
                parcelCond = parcelCond.and(P_AREA.greaterOrEqual(BigDecimal.valueOf(req.areaMin())));
            if (req.areaMax() != null)
                parcelCond = parcelCond.and(P_AREA.lessOrEqual(BigDecimal.valueOf(req.areaMax())));
            if (req.forestAreaMin() != null)
                parcelCond = parcelCond.and(field(name("p", "forest_area"), BigDecimal.class).greaterOrEqual(BigDecimal.valueOf(req.forestAreaMin())));
            if (req.forestAreaMax() != null)
                parcelCond = parcelCond.and(field(name("p", "forest_area"), BigDecimal.class).lessOrEqual(BigDecimal.valueOf(req.forestAreaMax())));
            if (req.arableAreaMin() != null)
                parcelCond = parcelCond.and(field(name("p", "arable_area"), BigDecimal.class).greaterOrEqual(BigDecimal.valueOf(req.arableAreaMin())));
            if (req.arableAreaMax() != null)
                parcelCond = parcelCond.and(field(name("p", "arable_area"), BigDecimal.class).lessOrEqual(BigDecimal.valueOf(req.arableAreaMax())));
            if (req.mustNotHaveForestPlan() != null && req.mustNotHaveForestPlan())
                parcelCond = parcelCond.and(field(name("p", "mk_date")).isNull());
            conditions.add(exists(selectOne().from(PARCEL).where(parcelCond)));
        }

        // Registry-derived work signals: created before any caller receives the owner.
        Condition signalBase = field(name("s", "organization_id"), UUID.class).eq(O_ORG)
            .and(field(name("s", "owner_id"), UUID.class).eq(O_ID))
            .and(field(name("s", "dismissed_at"), Instant.class).isNull());
        if (Boolean.TRUE.equals(req.mustHaveNewForestNotification()))
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s"))
                .where(signalBase.and(field(name("s", "signal_type"), String.class).eq("NEW_FOREST_NOTIFICATION")))));
        if (Boolean.TRUE.equals(req.mustHaveChangedForestPlan()))
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s"))
                .where(signalBase.and(field(name("s", "signal_type"), String.class).eq("NEW_FOREST_COMPARTMENT")))));
        if (Boolean.TRUE.equals(req.mustHaveOwnershipTransition()))
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s"))
                .where(signalBase.and(field(name("s", "signal_type"), String.class).eq("OWNERSHIP_TRANSITION")))));
        if (Boolean.TRUE.equals(req.mustHavePortfolioOwnershipAdded()))
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s"))
                .where(signalBase.and(field(name("s", "signal_type"), String.class).eq("PORTFOLIO_OWNERSHIP_ADDED")))));
        if (Boolean.TRUE.equals(req.mustHaveAnyDataSignal()))
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s")).where(signalBase)));
        if (req.dataChangedSince() != null)
            conditions.add(exists(selectOne().from(table(name("data_change_signal")).as("s"))
                .where(signalBase.and(field(name("s", "detected_at"), Instant.class).greaterOrEqual(Instant.ofEpochMilli(req.dataChangedSince()))))));
        if (req.hasNotificationsSince() != null)
            conditions.add(exists(selectOne().from(table(name("cadastre_notification")).as("n"))
                .where(field(name("n", "organization_id"), UUID.class).eq(O_ORG)
                    .and(field(name("n", "cadastral_code"), String.class).in(select(P_CODE).from(OWNER_PARCEL_RELATION).join(PARCEL).on(P_ID.eq(OPR_PARCEL).and(P_ORG.eq(OPR_ORG)))
                        .where(OPR_OWNER.eq(O_ID).and(OPR_ORG.eq(O_ORG)).and(OPR_ACTIVE.isTrue()))))
                    .and(field(name("n", "created_at"), Instant.class).greaterOrEqual(Instant.ofEpochMilli(req.hasNotificationsSince()))))));
        if (req.hasForestPlanSince() != null)
            conditions.add(exists(selectOne().from(table(name("forest_registry_feature")).as("f"))
                .where(field(name("f", "organization_id"), UUID.class).eq(O_ORG)
                    .and(field(name("f", "cadastral_code"), String.class).in(select(P_CODE).from(OWNER_PARCEL_RELATION).join(PARCEL).on(P_ID.eq(OPR_PARCEL).and(P_ORG.eq(OPR_ORG)))
                        .where(OPR_OWNER.eq(O_ID).and(OPR_ORG.eq(O_ORG)).and(OPR_ACTIVE.isTrue()))))
                    .and(field(name("f", "created_at"), Instant.class).greaterOrEqual(Instant.ofEpochMilli(req.hasForestPlanSince()))))));

        // Out-of-admin-search filtering
        if (req.statuses() == null || req.statuses().isEmpty()) {
            if (req.suspended() == null || req.suspended().isEmpty()) {
                conditions.add(
                    field(name("o", "out_of_admin_search_from")).isNull()
                    .or(field(name("o", "out_of_admin_search_from"), Instant.class).greaterThan(Instant.now()))
                    .or(field(name("o", "out_of_admin_search_from"), Instant.class).lessOrEqual(Instant.now())
                        .and(field(name("o", "out_of_admin_search_to")).isNotNull())
                        .and(field(name("o", "out_of_admin_search_to"), Instant.class).lessThan(Instant.now())))
                );
            } else {
                conditions.add(field(name("o", "out_of_admin_search_reason"), String.class).in(req.suspended()));
            }
        }

        // Interactive admin search is deliberately bounded. Large exports must
        // use a separate asynchronous/export path rather than one huge rich JSON response.
        int limit = req.maxResults() != null ? (int) Math.min(req.maxResults(), 500L) : 100;
        Condition combined = conditions.stream().reduce(Condition::and).orElse(noCondition());

        // Candidate-first: apply all indexed/business predicates and the row limit
        // before touching the 1.2M-row ownership relation table for enrichment.
        var candidates = dsl.select(O_ID, O_NAME, O_CODE, O_STATUS, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .from(OWNER)
            .where(combined)
            .orderBy(O_UPDATED.desc(), O_ID.asc())
            .limit(limit)
            .fetch();
        if (candidates.isEmpty()) return List.of();

        List<UUID> ownerIds = candidates.getValues(O_ID);
        Field<Integer> parcelCountField = count(OPR_PARCEL).as("parcel_count");
        Map<UUID, Long> parcelCounts = new HashMap<>();
        dsl.select(OPR_OWNER, parcelCountField)
            .from(OWNER_PARCEL_RELATION)
            .where(OPR_ORG.eq(orgId).and(OPR_ACTIVE.isTrue()).and(OPR_OWNER.in(ownerIds)))
            .groupBy(OPR_OWNER)
            .fetch()
            .forEach(row -> parcelCounts.put(
                row.get(OPR_OWNER),
                row.get(parcelCountField) == null ? 0L : row.get(parcelCountField).longValue()));

        Table<Record> pageSignals = table(name("data_change_signal")).as("sp");
        Field<UUID> SP_OWNER = field(name("sp", "owner_id"), UUID.class);
        Field<UUID> SP_ORG = field(name("sp", "organization_id"), UUID.class);
        Field<String> SP_TYPE = field(name("sp", "signal_type"), String.class);
        Field<Instant> SP_DISMISSED = field(name("sp", "dismissed_at"), Instant.class);
        Map<UUID, LinkedHashSet<String>> signalTypes = new HashMap<>();
        dsl.select(SP_OWNER, SP_TYPE)
            .from(pageSignals)
            .where(SP_ORG.eq(orgId).and(SP_DISMISSED.isNull()).and(SP_OWNER.in(ownerIds)))
            .orderBy(SP_OWNER.asc(), SP_TYPE.asc())
            .fetch()
            .forEach(row -> signalTypes
                .computeIfAbsent(row.get(SP_OWNER), ignored -> new LinkedHashSet<>())
                .add(row.get(SP_TYPE)));

        List<OwnerListItem> result = new ArrayList<>(candidates.size());
        for (var row : candidates) {
            UUID ownerId = row.get(O_ID);
            var signals = signalTypes.get(ownerId);
            result.add(new OwnerListItem(
                ownerId,
                row.get(O_NAME),
                row.get(O_CODE),
                row.get(O_STATUS),
                parcelCounts.getOrDefault(ownerId, 0L),
                row.get(O_UPDATED),
                row.get(O_ASSIGNED),
                row.get(O_PHONE),
                row.get(O_STATUS_SET),
                signals == null ? List.of() : List.copyOf(signals)
            ));
        }
        return result;
    }

    private boolean hasParcelFilter(AdminSearchRequest req) {
        return (req.counties() != null && !req.counties().isEmpty())
            || (req.municipalities() != null && !req.municipalities().isEmpty())
            || req.areaMin() != null || req.areaMax() != null
            || req.forestAreaMin() != null || req.forestAreaMax() != null
            || req.arableAreaMin() != null || req.arableAreaMax() != null
            || (req.mustNotHaveForestPlan() != null && req.mustNotHaveForestPlan());
    }

    private static List<String> splitSignals(String summary) {
        return summary == null || summary.isBlank() ? List.of() : List.of(summary.split(","));
    }

    // ===================== Admin prep data =====================

    public AdminPrepData getAdminPrepData(UUID orgId) {
        List<String> counties = dsl.selectDistinct(field(name("county"), String.class))
            .from(table("land_parcel")).where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("county")).isNotNull()))
            .orderBy(field(name("county"))).fetchInto(String.class);
        List<String> municipalities = dsl.selectDistinct(field(name("municipality"), String.class))
            .from(table("land_parcel")).where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("municipality")).isNotNull()))
            .orderBy(field(name("municipality"))).fetchInto(String.class);
        List<String> ownerTypes = dsl.selectDistinct(field(name("owner_type"), String.class))
            .from(table("owner")).where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("owner_type")).isNotNull()))
            .fetchInto(String.class);
        List<String> statuses = dsl.select(field(name("id"), String.class))
            .from(table("owner_status_definition")).where(field(name("organization_id"), UUID.class).eq(orgId))
            .fetchInto(String.class);
        return new AdminPrepData(counties, municipalities, List.of(), ownerTypes, statuses);
    }

    // ===================== Helpers =====================

    private Condition ownerCondition(UUID orgId, String search, String assignedSubject) {
        Condition c = O_ORG.eq(orgId);
        if (assignedSubject != null) c = c.and(O_ASSIGNED.eq(assignedSubject));
        if (search == null || search.isBlank()) return c;
        return c.and(condition("{0} @@ websearch_to_tsquery('simple', {1})", field(name("o", "search_document")), val(search.trim())));
    }

}
