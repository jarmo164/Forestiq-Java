package ee.forestiq.management;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.management.SalesManagementDtos.ForecastItem;
import ee.forestiq.management.SalesManagementDtos.ForecastReport;
import ee.forestiq.management.SalesManagementDtos.FunnelCase;
import ee.forestiq.management.SalesManagementDtos.FunnelReport;
import ee.forestiq.management.SalesManagementDtos.FunnelStep;
import ee.forestiq.management.SalesManagementDtos.StageProbability;
import ee.forestiq.management.SalesManagementDtos.UpdateStageProbabilityRequest;
import ee.forestiq.security.OrganizationContext;

@Service
public class SalesAnalyticsService {
    private static final Set<String> STAGES = Set.of(
        "QUALIFIED", "EVALUATION_REQUESTED", "EVALUATING", "VALUED", "OFFER_PREPARED",
        "OFFER_SENT", "NEGOTIATION", "WON", "LOST", "CANCELLED"
    );

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;

    public SalesAnalyticsService(DSLContext dsl, OrganizationContext orgCtx) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
    }

    @Transactional(readOnly = true)
    public FunnelReport funnel(Instant from, Instant to, String salesSubject) {
        Bounds bounds = bounds(from, to);
        List<FunnelCase> cases = funnelCases(bounds.from(), bounds.to(), salesSubject);
        FunnelStep qToE = step("QUALIFICATION_TO_EVALUATION", "Kvalifitseerimine → hindamine", cases,
            FunnelCase::qualifiedAt, FunnelCase::evaluationAt);
        FunnelStep eToO = step("EVALUATION_TO_OFFER", "Hindamine → pakkumine", cases,
            FunnelCase::evaluationAt, FunnelCase::offerAt);
        FunnelStep oToR = step("OFFER_TO_OUTCOME", "Pakkumine → leping / kaotus", cases,
            FunnelCase::offerAt, FunnelCase::outcomeAt);
        long won = cases.stream().filter(c -> "WON_CONTRACT".equals(c.outcome())).count();
        long lost = cases.stream().filter(c -> "LOST".equals(c.outcome())).count();
        long cancelled = cases.stream().filter(c -> "CANCELLED".equals(c.outcome())).count();
        return new FunnelReport(bounds.from(), bounds.to(), trim(salesSubject), List.of(qToE, eToO, oToR),
            won, lost, cancelled, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<FunnelCase> funnelCases(Instant from, Instant to, String salesSubject) {
        Bounds bounds = bounds(from, to);
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = trim(salesSubject);
        return dsl.fetch("""
            WITH milestones AS (
                SELECT d.id AS deal_id, d.owner_id, o.display_name AS owner_name, d.sales_subject,
                       d.stage AS current_stage,
                       MIN(e.created_at) FILTER (WHERE e.event_type = 'QUALIFIED') AS qualified_at,
                       MIN(e.created_at) FILTER (WHERE e.event_type = 'EVALUATION_REQUESTED') AS evaluation_at,
                       MIN(e.created_at) FILTER (WHERE e.event_type IN ('OFFER_PREPARED','OFFER_SENT')) AS offer_at,
                       MIN(e.created_at) FILTER (WHERE e.event_type = 'DEAL_LOST') AS lost_at,
                       MIN(e.created_at) FILTER (WHERE e.event_type = 'DEAL_CANCELLED') AS cancelled_at,
                       MIN(v.created_at) AS contract_at
                  FROM deal_case d
                  JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
                  LEFT JOIN deal_case_event e ON e.organization_id = d.organization_id AND e.deal_case_id = d.id
                  LEFT JOIN contract_version_audit v ON v.organization_id = d.organization_id AND v.deal_case_id = d.id
                 WHERE d.organization_id = ?
                 GROUP BY d.id, d.owner_id, o.display_name, d.sales_subject, d.stage
            )
            SELECT deal_id, owner_id, owner_name, sales_subject, current_stage, qualified_at, evaluation_at, offer_at,
                   CASE
                       WHEN contract_at IS NOT NULL THEN contract_at
                       WHEN lost_at IS NOT NULL THEN lost_at
                       ELSE cancelled_at
                   END AS outcome_at,
                   CASE
                       WHEN contract_at IS NOT NULL THEN 'WON_CONTRACT'
                       WHEN lost_at IS NOT NULL THEN 'LOST'
                       WHEN cancelled_at IS NOT NULL THEN 'CANCELLED'
                       ELSE NULL
                   END AS outcome
              FROM milestones
             WHERE qualified_at >= ? AND qualified_at < ?
               AND (CAST(? AS varchar) IS NULL OR sales_subject = CAST(? AS varchar))
             ORDER BY qualified_at DESC, deal_id
            """, orgId, db(bounds.from()), db(bounds.to()), subject, subject).map(row -> new FunnelCase(
            row.get("deal_id", UUID.class), row.get("owner_id", UUID.class), row.get("owner_name", String.class),
            row.get("sales_subject", String.class), row.get("current_stage", String.class),
            instant(row, "qualified_at"), instant(row, "evaluation_at"), instant(row, "offer_at"),
            instant(row, "outcome_at"), row.get("outcome", String.class)
        ));
    }

    @Transactional(readOnly = true)
    public List<StageProbability> probabilities(boolean includeHistory) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String historyPredicate = includeHistory ? "" : " AND valid_to IS NULL";
        return dsl.fetch("""
            SELECT stage, probability, version_no, valid_from, valid_to, created_by_subject
              FROM stage_probability_config
             WHERE organization_id = ?
            """ + historyPredicate + " ORDER BY stage, version_no DESC", orgId).map(this::probability);
    }

    @Transactional
    public StageProbability updateProbability(String rawStage, UpdateStageProbabilityRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String stage = normalizeStage(rawStage);
        BigDecimal probability = request == null ? null : request.probability();
        if (probability == null || probability.compareTo(BigDecimal.ZERO) < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Etapitõenäosus peab olema vahemikus 0 kuni 1.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Record current = dsl.fetchOne("""
            SELECT id, version_no FROM stage_probability_config
             WHERE organization_id = ? AND stage = ? AND valid_to IS NULL
             FOR UPDATE
            """, orgId, stage);
        int nextVersion = current == null ? 1 : current.get("version_no", Integer.class) + 1;
        if (current != null) {
            dsl.execute("UPDATE stage_probability_config SET valid_to = ? WHERE id = ?", now, current.get("id", UUID.class));
        }
        Record inserted = dsl.fetchOne("""
            INSERT INTO stage_probability_config
                (organization_id, stage, probability, version_no, valid_from, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING stage, probability, version_no, valid_from, valid_to, created_by_subject
            """, orgId, stage, probability.setScale(4, RoundingMode.HALF_UP), nextVersion, now, orgCtx.subject());
        return probability(inserted);
    }

    @Transactional(readOnly = true)
    public ForecastReport forecast(Instant from, Instant to, String salesSubject) {
        Bounds bounds = bounds(from, to);
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = trim(salesSubject);
        List<ForecastItem> items = dsl.fetch("""
            SELECT d.id AS deal_id, d.owner_id, o.display_name AS owner_name, d.stage,
                   p.probability, p.version_no,
                   d.price_expectation,
                   (SELECT de.recommended_purchase_price
                      FROM deal_evaluation de
                     WHERE de.organization_id = d.organization_id AND de.deal_case_id = d.id
                     ORDER BY de.revision DESC LIMIT 1) AS evaluation_value,
                   (SELECT NULLIF(e.event_data->>'amount','')::numeric
                      FROM deal_case_event e
                     WHERE e.organization_id = d.organization_id AND e.deal_case_id = d.id
                       AND e.event_type = 'OFFER_PREPARED'
                     ORDER BY e.created_at DESC, e.id DESC LIMIT 1) AS offered_value,
                   (SELECT NULLIF(e.event_data->>'amount','')::numeric
                      FROM deal_case_event e
                     WHERE e.organization_id = d.organization_id AND e.deal_case_id = d.id
                       AND e.event_type = 'DEAL_WON'
                     ORDER BY e.created_at DESC, e.id DESC LIMIT 1) AS won_value,
                   CASE
                     WHEN EXISTS (SELECT 1 FROM deal_case_event e WHERE e.organization_id=d.organization_id AND e.deal_case_id=d.id AND e.event_type='DEAL_WON') THEN 'WON'
                     WHEN EXISTS (SELECT 1 FROM deal_case_event e WHERE e.organization_id=d.organization_id AND e.deal_case_id=d.id AND e.event_type='DEAL_LOST') THEN 'LOST'
                     WHEN EXISTS (SELECT 1 FROM deal_case_event e WHERE e.organization_id=d.organization_id AND e.deal_case_id=d.id AND e.event_type='DEAL_CANCELLED') THEN 'CANCELLED'
                     ELSE NULL
                   END AS outcome
              FROM deal_case d
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
              LEFT JOIN stage_probability_config p
                ON p.organization_id = d.organization_id AND p.stage = d.stage AND p.valid_to IS NULL
             WHERE d.organization_id = ?
               AND EXISTS (
                   SELECT 1 FROM deal_case_event q
                    WHERE q.organization_id = d.organization_id AND q.deal_case_id = d.id
                      AND q.event_type = 'QUALIFIED' AND q.created_at >= ? AND q.created_at < ?
               )
               AND (CAST(? AS varchar) IS NULL OR d.sales_subject = CAST(? AS varchar))
             ORDER BY d.created_at DESC, d.id
            """, orgId, db(bounds.from()), db(bounds.to()), subject, subject).map(this::forecastItem);

        BigDecimal input = sum(items.stream().map(ForecastItem::inputValue).toList());
        BigDecimal weighted = sum(items.stream().map(ForecastItem::weightedValue).toList());
        BigDecimal won = sum(items.stream().map(ForecastItem::actualWonValue).toList());
        return new ForecastReport(bounds.from(), bounds.to(), subject, input, weighted, won,
            won.subtract(weighted), items, Instant.now());
    }

    private ForecastItem forecastItem(Record row) {
        BigDecimal won = row.get("won_value", BigDecimal.class);
        BigDecimal offered = row.get("offered_value", BigDecimal.class);
        BigDecimal evaluation = row.get("evaluation_value", BigDecimal.class);
        BigDecimal expectation = row.get("price_expectation", BigDecimal.class);
        String kind;
        BigDecimal input;
        if (won != null) { kind = "WON"; input = won; }
        else if (offered != null) { kind = "OFFER"; input = offered; }
        else if (evaluation != null) { kind = "EVALUATION"; input = evaluation; }
        else { kind = "EXPECTATION"; input = expectation == null ? BigDecimal.ZERO : expectation; }
        BigDecimal probability = row.get("probability", BigDecimal.class);
        if (probability == null) probability = defaultProbability(row.get("stage", String.class));
        Integer version = row.get("version_no", Integer.class);
        BigDecimal weighted = input.multiply(probability).setScale(2, RoundingMode.HALF_UP);
        return new ForecastItem(row.get("deal_id", UUID.class), row.get("owner_id", UUID.class),
            row.get("owner_name", String.class), row.get("stage", String.class), kind, input, probability,
            version == null ? 0 : version, weighted, won, row.get("outcome", String.class));
    }

    private FunnelStep step(String code, String label, List<FunnelCase> cases,
                            InstantGetter start, InstantGetter end) {
        List<FunnelCase> enteredCases = cases.stream().filter(c -> start.get(c) != null).toList();
        List<Long> durations = new ArrayList<>();
        long advanced = 0;
        for (FunnelCase item : enteredCases) {
            Instant startAt = start.get(item);
            Instant endAt = end.get(item);
            if (endAt != null && !endAt.isBefore(startAt)) {
                advanced++;
                durations.add(Duration.between(startAt, endAt).toMinutes());
            }
        }
        BigDecimal conversion = enteredCases.isEmpty() ? BigDecimal.ZERO
            : BigDecimal.valueOf(advanced).divide(BigDecimal.valueOf(enteredCases.size()), 4, RoundingMode.HALF_UP);
        BigDecimal medianHours = medianMinutes(durations).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return new FunnelStep(code, label, enteredCases.size(), advanced, conversion, medianHours);
    }

    private static BigDecimal medianMinutes(List<Long> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return BigDecimal.valueOf(sorted.get(middle));
        return BigDecimal.valueOf(sorted.get(middle - 1) + sorted.get(middle))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private StageProbability probability(Record row) {
        return new StageProbability(row.get("stage", String.class), row.get("probability", BigDecimal.class),
            row.get("version_no", Integer.class), instant(row, "valid_from"), instant(row, "valid_to"),
            row.get("created_by_subject", String.class));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal defaultProbability(String stage) {
        return switch (stage == null ? "" : stage) {
            case "QUALIFIED" -> new BigDecimal("0.10");
            case "EVALUATION_REQUESTED" -> new BigDecimal("0.20");
            case "EVALUATING" -> new BigDecimal("0.25");
            case "VALUED" -> new BigDecimal("0.40");
            case "OFFER_PREPARED" -> new BigDecimal("0.55");
            case "OFFER_SENT" -> new BigDecimal("0.65");
            case "NEGOTIATION" -> new BigDecimal("0.75");
            case "WON" -> BigDecimal.ONE;
            default -> BigDecimal.ZERO;
        };
    }

    private static String normalizeStage(String raw) {
        String stage = raw == null ? "" : raw.trim().toUpperCase();
        if (!STAGES.contains(stage)) throw new ResponseStatusException(BAD_REQUEST, "Tundmatu tehinguetapp.");
        return stage;
    }

    private static Bounds bounds(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(90, ChronoUnit.DAYS) : from;
        if (!start.isBefore(end)) throw new ResponseStatusException(BAD_REQUEST, "Raporti algus peab olema lõpust varasem.");
        if (Duration.between(start, end).toDays() > 3660) throw new ResponseStatusException(BAD_REQUEST, "Raporti periood on liiga pikk.");
        return new Bounds(start, end);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static OffsetDateTime db(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @FunctionalInterface
    private interface InstantGetter { Instant get(FunnelCase value); }
    private record Bounds(Instant from, Instant to) {}
}
