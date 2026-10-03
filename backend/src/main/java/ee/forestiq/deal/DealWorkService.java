package ee.forestiq.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.DealWorkDtos.DealWorkItem;
import ee.forestiq.deal.DealWorkDtos.DealWorkResponse;
import ee.forestiq.deal.DealWorkDtos.HealthReason;
import ee.forestiq.deal.DealWorkDtos.LossReasonDefinition;
import ee.forestiq.deal.DealWorkDtos.LossReportRow;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;

/**
 * Read-only active-deal work projection and health model.
 *
 * <p>All lifecycle mutations are owned by {@link DealLifecycleService}. Keeping this service
 * query-only protects the indexed active-work path from command-side dependencies.</p>
 */
@Service
public class DealWorkService {
    private static final Set<String> EVALUATION_STAGES = Set.of("EVALUATION_REQUESTED", "EVALUATING");
    private static final long INACTIVITY_DAYS = 7;
    private static final long EXPIRING_OFFER_DAYS = 7;

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final OwnerCollaborationPort ownerCollaboration;

    public DealWorkService(DSLContext dsl, OrganizationContext organizationContext,
                           OwnerCollaborationPort ownerCollaboration) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.ownerCollaboration = ownerCollaboration;
    }

    @Transactional(readOnly = true)
    public DealWorkResponse list(String stage, String assignee, String health, String due,
                                 BigDecimal minValue, BigDecimal maxValue) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        String ownerRestriction = ownerCollaboration.assignedSubjectRestriction().orElse(null);
        String userScope = ownerCollaboration.hasOrganizationWideAccess() ? null : organizationContext.subject();
        String stageFilter = blank(stage);
        String assigneeFilter = blank(assignee);

        List<DealWorkItem> all = dsl.fetch("""
            SELECT d.id, d.owner_id, o.display_name AS owner_name, d.stage, d.sale_subject,
                   d.sales_subject, d.evaluator_subject, d.next_action, d.next_action_due_at,
                   d.evaluation_due_at, d.price_expectation,
                   (SELECT de.recommended_purchase_price
                      FROM deal_evaluation de
                     WHERE de.organization_id = d.organization_id AND de.deal_case_id = d.id
                     ORDER BY de.revision DESC LIMIT 1) AS evaluation_value,
                   (SELECT CAST(NULLIF(e.event_data->>'validUntil', '') AS date)
                      FROM deal_case_event e
                     WHERE e.organization_id = d.organization_id AND e.deal_case_id = d.id
                       AND e.event_type = 'OFFER_PREPARED'
                     ORDER BY e.created_at DESC, e.id DESC LIMIT 1) AS offer_valid_until,
                   (SELECT MAX(e.created_at) FROM deal_case_event e
                     WHERE e.organization_id = d.organization_id AND e.deal_case_id = d.id) AS last_activity_at,
                   (SELECT string_agg(p.cadastral_code, ',' ORDER BY p.cadastral_code)
                      FROM deal_case_parcel dp
                      JOIN land_parcel p ON p.organization_id = dp.organization_id AND p.id = dp.parcel_id
                     WHERE dp.organization_id = d.organization_id AND dp.deal_case_id = d.id) AS cadastral_codes
              FROM deal_case d
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE d.organization_id = ?
               AND d.closed_at IS NULL
               AND d.stage NOT IN ('WON','LOST','CANCELLED')
               AND (CAST(? AS varchar) IS NULL OR o.assigned_to_subject = CAST(? AS varchar))
               AND (CAST(? AS varchar) IS NULL OR d.sales_subject = CAST(? AS varchar) OR d.evaluator_subject = CAST(? AS varchar))
               AND (CAST(? AS varchar) IS NULL OR d.stage = CAST(? AS varchar))
               AND (CAST(? AS varchar) IS NULL OR d.sales_subject = CAST(? AS varchar) OR d.evaluator_subject = CAST(? AS varchar))
             ORDER BY COALESCE(d.next_action_due_at, d.evaluation_due_at) NULLS LAST, d.updated_at DESC, d.id
             LIMIT 1000
            """, organizationId,
            ownerRestriction, ownerRestriction,
            userScope, userScope, userScope,
            stageFilter, stageFilter,
            assigneeFilter, assigneeFilter, assigneeFilter).map(this::toWorkItem);

        List<DealWorkItem> filtered = all.stream()
            .filter(item -> minValue == null || (item.value() != null && item.value().compareTo(minValue) >= 0))
            .filter(item -> maxValue == null || (item.value() != null && item.value().compareTo(maxValue) <= 0))
            .filter(item -> health == null || health.isBlank() || item.health().equalsIgnoreCase(health.trim()))
            .filter(item -> matchesDue(item, due))
            .toList();
        return new DealWorkResponse(filtered, filtered.size());
    }

    @Transactional(readOnly = true)
    public DealWorkItem get(UUID dealId) {
        return list(null, null, null, null, null, null).items().stream()
            .filter(item -> item.dealId().equals(dealId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Tehingut ei leitud aktiivsest töövaatest."));
    }

    @Transactional(readOnly = true)
    public List<LossReasonDefinition> lossReasons(boolean includeInactive) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        return dsl.fetch("""
            SELECT code, label, active, sort_order FROM deal_loss_reason_definition
             WHERE organization_id = ? AND (CAST(? AS boolean) OR active = TRUE)
             ORDER BY sort_order, label, code
            """, organizationId, includeInactive).map(row -> new LossReasonDefinition(
            row.get("code", String.class), row.get("label", String.class),
            Boolean.TRUE.equals(row.get("active", Boolean.class)), row.get("sort_order", Integer.class)));
    }

    @Transactional(readOnly = true)
    public List<LossReportRow> lossReport(Instant from, Instant to, String salesSubject, String lostFromStage) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        Instant start = from == null ? Instant.now().minus(90, ChronoUnit.DAYS) : from;
        Instant end = to == null ? Instant.now() : to;
        String salesFilter = blank(salesSubject);
        String stageFilter = blank(lostFromStage);
        return dsl.fetch("""
            SELECT o.reason_code, d.label, count(*) AS cnt
              FROM deal_loss_outcome o
              JOIN deal_loss_reason_definition d
                ON d.organization_id = o.organization_id AND d.code = o.reason_code
             WHERE o.organization_id = ? AND o.created_at >= ? AND o.created_at < ?
               AND (CAST(? AS varchar) IS NULL OR o.sales_subject = CAST(? AS varchar))
               AND (CAST(? AS varchar) IS NULL OR o.lost_from_stage = CAST(? AS varchar))
             GROUP BY o.reason_code, d.label
             ORDER BY cnt DESC, d.label
            """, organizationId, start, end, salesFilter, salesFilter, stageFilter, stageFilter)
            .map(row -> new LossReportRow(row.get("reason_code", String.class),
                row.get("label", String.class), row.get("cnt", Long.class)));
    }

    private DealWorkItem toWorkItem(Record row) {
        Instant now = Instant.now();
        String stage = row.get("stage", String.class);
        Instant nextDue = instant(row, "next_action_due_at");
        Instant evaluationDue = instant(row, "evaluation_due_at");
        Instant lastActivity = instant(row, "last_activity_at");
        LocalDate offerValid = row.get("offer_valid_until", LocalDate.class);
        List<HealthReason> reasons = healthReasons(stage, row.get("next_action", String.class), nextDue,
            evaluationDue, offerValid, lastActivity, now);
        String health = reasons.stream().anyMatch(reason -> "CRITICAL".equals(reason.severity())) ? "CRITICAL"
            : reasons.isEmpty() ? "HEALTHY" : "ATTENTION";
        BigDecimal value = row.get("evaluation_value", BigDecimal.class) != null
            ? row.get("evaluation_value", BigDecimal.class) : row.get("price_expectation", BigDecimal.class);
        String codes = row.get("cadastral_codes", String.class);
        return new DealWorkItem(row.get("id", UUID.class), row.get("owner_id", UUID.class),
            row.get("owner_name", String.class), stage, row.get("sale_subject", String.class),
            codes == null || codes.isBlank() ? List.of() : Arrays.asList(codes.split(",")), value,
            row.get("sales_subject", String.class), row.get("evaluator_subject", String.class),
            row.get("next_action", String.class), nextDue, evaluationDue, offerValid, lastActivity, health, reasons);
    }

    static List<HealthReason> healthReasons(String stage, String nextAction, Instant nextDue, Instant evaluationDue,
                                            LocalDate offerValidUntil, Instant lastActivity, Instant now) {
        List<HealthReason> reasons = new ArrayList<>();
        if (nextAction == null || nextAction.isBlank() || nextDue == null) {
            reasons.add(new HealthReason("MISSING_NEXT_ACTION", "CRITICAL", "Järgmine samm puudub",
                "Aktiivsel tehingul peab olema tegevus ja tähtaeg.", null,
                "Määra järgmine tegevus ja tähtaeg."));
        } else if (nextDue.isBefore(now)) {
            reasons.add(new HealthReason("NEXT_ACTION_OVERDUE", "CRITICAL", "Järgmine tegevus on tähtaja ületanud",
                "next_action_due_at < praegune aeg", nextDue,
                "Tee tegevus kohe või määra põhjendatult uus tähtaeg."));
        }
        if (EVALUATION_STAGES.contains(stage) && evaluationDue != null && evaluationDue.isBefore(now)) {
            reasons.add(new HealthReason("EVALUATION_SLA_OVERDUE", "CRITICAL", "Hindamine on üle SLA",
                "Aktiivse hindamise evaluation_due_at on möödunud.", evaluationDue,
                "Lõpeta hindamine või lahenda blokeeriv andmelünk."));
        }
        if (offerValidUntil != null) {
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (offerValidUntil.isBefore(today)) {
                reasons.add(new HealthReason("OFFER_EXPIRED", "CRITICAL", "Pakkumine on aegunud",
                    "Pakkumise validUntil on minevikus.", offerValidUntil.atStartOfDay().toInstant(ZoneOffset.UTC),
                    "Valmista uus versioon või sulge võimalus põhjendusega."));
            } else if (!offerValidUntil.isAfter(today.plusDays(EXPIRING_OFFER_DAYS))) {
                reasons.add(new HealthReason("OFFER_EXPIRING", "ATTENTION", "Pakkumise tähtaeg läheneb",
                    "Pakkumine aegub järgmise 7 päeva jooksul.",
                    offerValidUntil.atStartOfDay().toInstant(ZoneOffset.UTC),
                    "Kontrolli kliendi vastus enne pakkumise aegumist."));
            }
        }
        if (lastActivity != null && lastActivity.isBefore(now.minus(INACTIVITY_DAYS, ChronoUnit.DAYS))) {
            reasons.add(new HealthReason("INACTIVE", "ATTENTION", "Tehing on olnud tegevuseta",
                "Viimasest tehingusündmusest on möödunud üle 7 päeva.", lastActivity,
                "Kontrolli, kas järgmine samm on endiselt kehtiv."));
        }
        return List.copyOf(reasons);
    }

    private boolean matchesDue(DealWorkItem item, String due) {
        String filter = blank(due);
        if (filter == null || "ALL".equalsIgnoreCase(filter)) return true;
        Instant candidate = item.nextActionDueAt() != null ? item.nextActionDueAt() : item.evaluationDueAt();
        if (candidate == null) return "MISSING".equalsIgnoreCase(filter);
        Instant now = Instant.now();
        return switch (filter.toUpperCase(Locale.ROOT)) {
            case "OVERDUE" -> candidate.isBefore(now);
            case "NEXT7" -> !candidate.isBefore(now) && candidate.isBefore(now.plus(7, ChronoUnit.DAYS));
            default -> true;
        };
    }

    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
