package ee.forestiq.deal.workflow;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/** Deal-owned adapter for workflow linkage, timeline events and canonical deal history projection. */
@Service
public class WorkflowDealService implements WorkflowDealPort {

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;

    public WorkflowDealService(DSLContext dsl, OrganizationContext organizationContext) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
    }

    @Override
    @Transactional(readOnly = true)
    public DealReference requireDeal(UUID dealId) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        Record row = dsl.fetchOne("""
            SELECT owner_id
              FROM deal_case
             WHERE organization_id = ? AND id = ?
            """, organizationId, dealId);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "Tehingut ei leitud.");
        }
        return new DealReference(dealId, row.get("owner_id", UUID.class));
    }

    @Override
    @Transactional
    public void appendEvent(UUID dealId, TimelineEvent event) {
        dsl.execute("""
            INSERT INTO deal_case_event
                (organization_id, deal_case_id, event_type, description, event_data, created_by_subject)
            VALUES (?, ?, ?, ?, jsonb_build_object(
                'sourceType', ?, 'channel', ?, 'outcomeCode', ?, 'reason', ?, 'activityId', ?
            ), ?)
            """,
            organizationContext.requiredOrganizationId(),
            dealId,
            event.eventType(),
            event.description(),
            event.sourceType(),
            event.channel(),
            event.outcomeCode(),
            event.reason(),
            event.activityId() == null ? null : event.activityId().toString(),
            organizationContext.subject());
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryPage history(UUID dealId, HistoryQuery query) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        int limit = clampLimit(query.requestedLimit());
        validateCursor(query.cursorCreatedAt(), query.cursorId());

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT * FROM (
                SELECT id, event_type AS activity_type,
                       COALESCE(event_data->>'sourceType', 'AUTOMATED') AS source_type,
                       description AS content,
                       event_data->>'channel' AS contact_channel,
                       event_data->>'outcomeCode' AS outcome_code,
                       event_data->>'reason' AS reason,
                       created_by_subject, created_at,
                       CASE WHEN event_data->>'activityId' ~ '^[0-9]+$'
                            THEN (event_data->>'activityId')::bigint ELSE NULL END AS related_reminder_id,
                       deal_case_id
                FROM deal_case_event
                WHERE organization_id = ? AND deal_case_id = ?
            ) history
            WHERE 1 = 1
            """);
        params.add(organizationId);
        params.add(dealId);
        appendFilters(sql, params, query);
        appendCursor(sql, params, query.cursorCreatedAt(), query.cursorId());
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit + 1);

        Result<Record> rows = dsl.fetch(sql.toString(), params.toArray());
        boolean hasMore = rows.size() > limit;
        List<Record> selected = rows.stream().limit(limit).toList();
        List<HistoryItem> items = selected.stream().map(row -> new HistoryItem(
            row.get("id", UUID.class),
            row.get("activity_type", String.class),
            row.get("source_type", String.class),
            row.get("content", String.class),
            row.get("contact_channel", String.class),
            row.get("outcome_code", String.class),
            row.get("reason", String.class),
            row.get("created_by_subject", String.class),
            toInstant(row, "created_at"),
            row.get("related_reminder_id", Long.class),
            row.get("deal_case_id", UUID.class))).toList();

        if (!hasMore || selected.isEmpty()) {
            return new HistoryPage(items, null, null);
        }
        Record last = selected.get(selected.size() - 1);
        return new HistoryPage(items, toInstant(last, "created_at"), last.get("id", UUID.class));
    }

    private static void appendFilters(StringBuilder sql, List<Object> params, HistoryQuery query) {
        if (StringUtils.hasText(query.activityType())) {
            sql.append(" AND activity_type = ?");
            params.add(query.activityType().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.outcome())) {
            sql.append(" AND outcome_code = ?");
            params.add(query.outcome().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(query.author())) {
            sql.append(" AND created_by_subject = ?");
            params.add(query.author().trim());
        }
        if (query.from() != null) {
            sql.append(" AND created_at >= ?");
            params.add(utc(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND created_at < ?");
            params.add(utc(query.to()));
        }
    }

    private static void appendCursor(
        StringBuilder sql,
        List<Object> params,
        Instant cursorCreatedAt,
        UUID cursorId) {
        if (cursorCreatedAt == null) {
            return;
        }
        sql.append(" AND (created_at, id) < (?, ?)");
        params.add(utc(cursorCreatedAt));
        params.add(cursorId);
    }

    private static void validateCursor(Instant cursorCreatedAt, UUID cursorId) {
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "Ajaloopäringu kursor vajab nii aega kui ID-d.");
        }
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return 50;
        }
        return Math.min(requested, 100);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
