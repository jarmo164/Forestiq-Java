package ee.forestiq.management;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PRECONDITION_FAILED;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.security.OrganizationContext;

@Service
public class DataQualityService {

    private static final Set<OrganizationRole> ASSIGNABLE_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);
    private static final Set<String> ISSUE_TYPES = Set.of(
        "MISSING_CONTACT", "STALE_OWNER_DATA", "DUPLICATE_CONTACT", "MISSING_DEAL_INFO");
    private static final Set<String> IMPACTS = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Set<String> STATUSES = Set.of("OPEN", "ASSIGNED", "RESOLVED");
    private static final Set<String> ACTIVE_DEAL_STAGES = Set.of(
        "QUALIFIED", "EVALUATION_REQUESTED", "EVALUATING", "VALUED", "OFFER_PREPARED", "OFFER_SENT", "NEGOTIATION");

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OrganizationIdentityDirectory identityDirectory;
    private final ObjectMapper objectMapper;

    public DataQualityService(DSLContext dsl, OrganizationContext orgCtx,
                              OrganizationIdentityDirectory identityDirectory, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.identityDirectory = identityDirectory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScanSummary refresh() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String actor = orgCtx.subject();
        UUID scanId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.execute("""
            INSERT INTO data_quality_scan_run
                (id, organization_id, status, created_by_subject, started_at)
            VALUES (?, ?, 'RUNNING', ?, ?)
            """, scanId, orgId, actor, now);

        int detected = 0;
        detected += detectMissingContacts(orgId, scanId, actor, now);
        detected += detectStaleOwners(orgId, scanId, actor, now);
        detected += detectDuplicateContacts(orgId, scanId, actor, now);
        detected += detectMissingDealInfo(orgId, scanId, actor, now);

        int autoResolved = dsl.execute("""
            UPDATE data_quality_issue
               SET status = 'RESOLVED', resolved_at = ?,
                   action_reason = 'Automaatkontroll ei tuvasta rikkumist enam.',
                   updated_by_subject = 'system-data-quality', updated_at = ?
             WHERE organization_id = ?
               AND status <> 'RESOLVED'
               AND last_scan_id IS DISTINCT FROM ?
            """, now, now, orgId, scanId);

        dsl.execute("""
            UPDATE data_quality_scan_run
               SET status = 'SUCCESS', detected_count = ?, auto_resolved_count = ?, finished_at = ?
             WHERE organization_id = ? AND id = ?
            """, detected, autoResolved, now, orgId, scanId);
        return new ScanSummary(scanId, detected, autoResolved, now.toInstant());
    }

    @Transactional(readOnly = true)
    public QueueResponse queue(String issueType, String status, String impact, String assignedSubject, int requestedLimit) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String typeFilter = normalize(issueType, ISSUE_TYPES, "probleemi tüüp");
        String statusFilter = normalize(status, STATUSES, "olek");
        String impactFilter = normalize(impact, IMPACTS, "mõju");
        String assigneeFilter = blank(assignedSubject);
        int limit = Math.max(1, Math.min(requestedLimit, 200));

        List<Object> params = new ArrayList<>();
        params.add(orgId);
        StringBuilder where = new StringBuilder(" WHERE q.organization_id = ? ");
        appendEquals(where, params, "q.issue_type", typeFilter);
        appendEquals(where, params, "q.status", statusFilter);
        appendEquals(where, params, "q.impact", impactFilter);
        appendEquals(where, params, "q.assigned_subject", assigneeFilter);

        Long total = dsl.fetchOne("SELECT count(*) AS cnt FROM data_quality_issue q" + where, params.toArray())
            .get("cnt", Long.class);

        String sql = """
            SELECT q.id, q.owner_id, o.display_name AS owner_name, q.deal_case_id,
                   q.issue_type, q.source_name, q.source_key, q.summary, q.impact,
                   q.recommended_subject, q.assigned_subject, q.status,
                   q.first_detected_at, q.last_detected_at, q.resolved_at,
                   q.action_reason, q.updated_by_subject, q.updated_at
              FROM data_quality_issue q
              JOIN owner o ON o.organization_id = q.organization_id AND o.id = q.owner_id
            """ + where + """
             ORDER BY CASE q.impact
                        WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                      CASE q.status WHEN 'OPEN' THEN 1 WHEN 'ASSIGNED' THEN 2 ELSE 3 END,
                      q.first_detected_at, q.id
             LIMIT ?
            """;
        params.add(limit);
        List<DataQualityItem> items = dsl.fetch(sql, params.toArray()).map(this::item);

        Record counts = dsl.fetchOne("""
            SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_count,
                   count(*) FILTER (WHERE status = 'ASSIGNED') AS assigned_count,
                   count(*) FILTER (WHERE status = 'RESOLVED') AS resolved_count,
                   count(*) FILTER (WHERE status <> 'RESOLVED' AND impact = 'CRITICAL') AS critical_count
              FROM data_quality_issue WHERE organization_id = ?
            """, orgId);
        return new QueueResponse(items, total == null ? 0 : total,
            longValue(counts, "open_count"), longValue(counts, "assigned_count"),
            longValue(counts, "resolved_count"), longValue(counts, "critical_count"));
    }

    @Transactional(readOnly = true)
    public List<DataQualityEvent> history(UUID issueId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireIssue(orgId, issueId, false);
        return dsl.fetch("""
            SELECT id, event_type, from_status, to_status, reason, actor_subject, snapshot, created_at
              FROM data_quality_issue_event
             WHERE organization_id = ? AND issue_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT 200
            """, orgId, issueId).map(row -> new DataQualityEvent(
                row.get("id", UUID.class), row.get("event_type", String.class),
                row.get("from_status", String.class), row.get("to_status", String.class),
                row.get("reason", String.class), row.get("actor_subject", String.class),
                json(row.get("snapshot", JSONB.class)), instant(row, "created_at")));
    }

    @Transactional
    public DataQualityItem assign(UUID issueId, AssignRequest request) {
        if (request == null || blank(request.assigneeSubject()) == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Vastutaja on kohustuslik.");
        }
        UUID orgId = orgCtx.requiredOrganizationId();
        Record issue = requireIssue(orgId, issueId, true);
        if ("RESOLVED".equals(issue.get("status", String.class))) {
            throw new ResponseStatusException(PRECONDITION_FAILED, "Lahendatud probleemi ei saa määrata enne uut tuvastust.");
        }
        String subject = request.assigneeSubject().trim();
        identityDirectory.requireAssignableUser(orgId, subject, ASSIGNABLE_ROLES);
        String reason = blank(request.reason());
        dsl.execute("""
            UPDATE data_quality_issue
               SET assigned_subject = ?, status = 'ASSIGNED', action_reason = ?,
                   updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, subject, reason == null ? "Probleem määrati vastutajale." : reason,
            orgCtx.subject(), orgId, issueId);
        return get(orgId, issueId);
    }

    @Transactional
    public DataQualityItem release(UUID issueId, ActionRequest request) {
        String reason = requiredReason(request, "Vabastamise põhjus on kohustuslik.");
        UUID orgId = orgCtx.requiredOrganizationId();
        Record issue = requireIssue(orgId, issueId, true);
        if ("RESOLVED".equals(issue.get("status", String.class))) {
            throw new ResponseStatusException(PRECONDITION_FAILED, "Lahendatud probleemi ei saa vabastada.");
        }
        if (issue.get("assigned_subject", String.class) == null) {
            throw new ResponseStatusException(PRECONDITION_FAILED, "Probleem ei ole praegu kellelegi määratud.");
        }
        dsl.execute("""
            UPDATE data_quality_issue
               SET assigned_subject = NULL, status = 'OPEN', action_reason = ?,
                   updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, reason, orgCtx.subject(), orgId, issueId);
        return get(orgId, issueId);
    }

    @Transactional
    public DataQualityItem resolve(UUID issueId, ActionRequest request) {
        String reason = requiredReason(request, "Lahenduse põhjendus on kohustuslik.");
        UUID orgId = orgCtx.requiredOrganizationId();
        Record issue = requireIssue(orgId, issueId, true);
        if ("RESOLVED".equals(issue.get("status", String.class))) return item(issueWithOwner(orgId, issueId));
        dsl.execute("""
            UPDATE data_quality_issue
               SET status = 'RESOLVED', resolved_at = now(), action_reason = ?,
                   updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, reason, orgCtx.subject(), orgId, issueId);
        return get(orgId, issueId);
    }

    private int detectMissingContacts(UUID orgId, UUID scanId, String actor, OffsetDateTime now) {
        return dsl.execute("""
            INSERT INTO data_quality_issue
                (organization_id, owner_id, issue_type, source_name, source_key, summary, impact,
                 recommended_subject, status, first_detected_at, last_detected_at, last_scan_id,
                 updated_by_subject, created_at, updated_at)
            SELECT o.organization_id, o.id, 'MISSING_CONTACT', 'ForestIQ CRM', 'owner:' || o.id,
                   'Omanikul puuduvad nii telefon kui ka e-post.',
                   CASE WHEN EXISTS (
                       SELECT 1 FROM deal_case d
                        WHERE d.organization_id = o.organization_id AND d.owner_id = o.id
                          AND d.stage = ANY(?::varchar[])
                   ) THEN 'CRITICAL' ELSE 'HIGH' END,
                   COALESCE(o.assigned_to_subject,
                       (SELECT d.sales_subject FROM deal_case d
                         WHERE d.organization_id = o.organization_id AND d.owner_id = o.id
                           AND d.stage = ANY(?::varchar[])
                         ORDER BY d.updated_at DESC LIMIT 1), ?),
                   'OPEN', ?, ?, ?, 'system-data-quality', ?, ?
              FROM owner o
             WHERE o.organization_id = ?
               AND COALESCE(btrim(o.phone), '') = ''
               AND COALESCE(btrim(o.email), '') = ''
               AND COALESCE(o.status, '') <> 'DECEASED'
            ON CONFLICT (organization_id, issue_type, source_key) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                source_name = EXCLUDED.source_name,
                summary = EXCLUDED.summary,
                impact = EXCLUDED.impact,
                recommended_subject = EXCLUDED.recommended_subject,
                status = CASE WHEN data_quality_issue.status = 'RESOLVED'
                              THEN CASE WHEN data_quality_issue.assigned_subject IS NULL THEN 'OPEN' ELSE 'ASSIGNED' END
                              ELSE data_quality_issue.status END,
                resolved_at = CASE WHEN data_quality_issue.status = 'RESOLVED' THEN NULL ELSE data_quality_issue.resolved_at END,
                action_reason = CASE WHEN data_quality_issue.status = 'RESOLVED'
                                     THEN 'Reegel tuvastas rikkumise uuesti või jätkuvalt.'
                                     ELSE data_quality_issue.action_reason END,
                last_detected_at = EXCLUDED.last_detected_at,
                last_scan_id = EXCLUDED.last_scan_id,
                updated_by_subject = 'system-data-quality',
                updated_at = EXCLUDED.updated_at
            """, ACTIVE_DEAL_STAGES.toArray(String[]::new), ACTIVE_DEAL_STAGES.toArray(String[]::new), actor,
            now, now, scanId, now, now, orgId);
    }

    private int detectStaleOwners(UUID orgId, UUID scanId, String actor, OffsetDateTime now) {
        return dsl.execute("""
            INSERT INTO data_quality_issue
                (organization_id, owner_id, issue_type, source_name, source_key, summary, impact,
                 recommended_subject, status, first_detected_at, last_detected_at, last_scan_id,
                 updated_by_subject, created_at, updated_at)
            SELECT o.organization_id, o.id, 'STALE_OWNER_DATA', 'ForestIQ CRM + MetsIS portfell', 'owner:' || o.id,
                   'Aktiivse võimalusega omaniku CRM/MetsIS andmed on üle 180 päeva värskendamata.',
                   'HIGH',
                   COALESCE(o.assigned_to_subject,
                       (SELECT d.sales_subject FROM deal_case d
                         WHERE d.organization_id = o.organization_id AND d.owner_id = o.id
                           AND d.stage = ANY(?::varchar[])
                         ORDER BY d.updated_at DESC LIMIT 1), ?),
                   'OPEN', ?, ?, ?, 'system-data-quality', ?, ?
              FROM owner o
              LEFT JOIN LATERAL (
                  SELECT max(r.source_seen_at) AS last_source_seen
                    FROM owner_parcel_relation r
                   WHERE r.organization_id = o.organization_id AND r.owner_id = o.id AND r.active = TRUE
              ) freshness ON TRUE
             WHERE o.organization_id = ?
               AND EXISTS (
                   SELECT 1 FROM deal_case d
                    WHERE d.organization_id = o.organization_id AND d.owner_id = o.id
                      AND d.stage = ANY(?::varchar[])
               )
               AND GREATEST(o.updated_at, COALESCE(freshness.last_source_seen, o.updated_at)) < now() - interval '180 days'
            ON CONFLICT (organization_id, issue_type, source_key) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                source_name = EXCLUDED.source_name,
                summary = EXCLUDED.summary,
                impact = EXCLUDED.impact,
                recommended_subject = EXCLUDED.recommended_subject,
                status = CASE WHEN data_quality_issue.status = 'RESOLVED'
                              THEN CASE WHEN data_quality_issue.assigned_subject IS NULL THEN 'OPEN' ELSE 'ASSIGNED' END
                              ELSE data_quality_issue.status END,
                resolved_at = CASE WHEN data_quality_issue.status = 'RESOLVED' THEN NULL ELSE data_quality_issue.resolved_at END,
                action_reason = CASE WHEN data_quality_issue.status = 'RESOLVED'
                                     THEN 'Reegel tuvastas rikkumise uuesti või jätkuvalt.'
                                     ELSE data_quality_issue.action_reason END,
                last_detected_at = EXCLUDED.last_detected_at,
                last_scan_id = EXCLUDED.last_scan_id,
                updated_by_subject = 'system-data-quality',
                updated_at = EXCLUDED.updated_at
            """, ACTIVE_DEAL_STAGES.toArray(String[]::new), actor, now, now, scanId, now, now,
            orgId, ACTIVE_DEAL_STAGES.toArray(String[]::new));
    }

    private int detectDuplicateContacts(UUID orgId, UUID scanId, String actor, OffsetDateTime now) {
        return dsl.execute("""
            WITH duplicate_email AS (
                SELECT organization_id, lower(btrim(email)) AS contact_key
                  FROM owner
                 WHERE organization_id = ? AND email IS NOT NULL AND btrim(email) <> ''
                 GROUP BY organization_id, lower(btrim(email)) HAVING count(*) > 1
            ), duplicate_phone AS (
                SELECT organization_id, btrim(phone) AS contact_key
                  FROM owner
                 WHERE organization_id = ? AND phone IS NOT NULL AND btrim(phone) <> ''
                 GROUP BY organization_id, btrim(phone) HAVING count(*) > 1
            )
            INSERT INTO data_quality_issue
                (organization_id, owner_id, issue_type, source_name, source_key, summary, impact,
                 recommended_subject, status, first_detected_at, last_detected_at, last_scan_id,
                 updated_by_subject, created_at, updated_at)
            SELECT DISTINCT o.organization_id, o.id, 'DUPLICATE_CONTACT', 'ForestIQ CRM kontaktidentiteet',
                   'owner:' || o.id,
                   'Sama e-posti aadress või telefon esineb vähemalt kahel erineval omanikul. Kontrolli kirjeid käsitsi; automaatset ühendamist ei tehta.',
                   'MEDIUM', COALESCE(o.assigned_to_subject, ?),
                   'OPEN', ?, ?, ?, 'system-data-quality', ?, ?
              FROM owner o
              LEFT JOIN duplicate_email de
                ON de.organization_id = o.organization_id AND de.contact_key = lower(btrim(o.email))
              LEFT JOIN duplicate_phone dp
                ON dp.organization_id = o.organization_id AND dp.contact_key = btrim(o.phone)
             WHERE o.organization_id = ? AND (de.contact_key IS NOT NULL OR dp.contact_key IS NOT NULL)
            ON CONFLICT (organization_id, issue_type, source_key) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                source_name = EXCLUDED.source_name,
                summary = EXCLUDED.summary,
                impact = EXCLUDED.impact,
                recommended_subject = EXCLUDED.recommended_subject,
                status = CASE WHEN data_quality_issue.status = 'RESOLVED'
                              THEN CASE WHEN data_quality_issue.assigned_subject IS NULL THEN 'OPEN' ELSE 'ASSIGNED' END
                              ELSE data_quality_issue.status END,
                resolved_at = CASE WHEN data_quality_issue.status = 'RESOLVED' THEN NULL ELSE data_quality_issue.resolved_at END,
                action_reason = CASE WHEN data_quality_issue.status = 'RESOLVED'
                                     THEN 'Reegel tuvastas rikkumise uuesti või jätkuvalt.'
                                     ELSE data_quality_issue.action_reason END,
                last_detected_at = EXCLUDED.last_detected_at,
                last_scan_id = EXCLUDED.last_scan_id,
                updated_by_subject = 'system-data-quality',
                updated_at = EXCLUDED.updated_at
            """, orgId, orgId, actor, now, now, scanId, now, now, orgId);
    }

    private int detectMissingDealInfo(UUID orgId, UUID scanId, String actor, OffsetDateTime now) {
        return dsl.execute("""
            INSERT INTO data_quality_issue
                (organization_id, owner_id, deal_case_id, issue_type, source_name, source_key, summary, impact,
                 recommended_subject, status, first_detected_at, last_detected_at, last_scan_id,
                 updated_by_subject, created_at, updated_at)
            SELECT d.organization_id, d.owner_id, d.id, 'MISSING_DEAL_INFO', 'ForestIQ tehinguvoog',
                   'deal:' || d.id,
                   CASE WHEN d.stage IN ('EVALUATION_REQUESTED','EVALUATING') AND d.evaluation_due_at IS NULL
                        THEN 'Aktiivsel hindamisel puudub hindamise tähtaeg.'
                        ELSE 'Aktiivsel tehingul puudub järgmine tegevus või selle tähtaeg.' END,
                   'CRITICAL', COALESCE(d.sales_subject, o.assigned_to_subject, ?),
                   'OPEN', ?, ?, ?, 'system-data-quality', ?, ?
              FROM deal_case d
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE d.organization_id = ?
               AND d.stage = ANY(?::varchar[])
               AND (
                    (d.stage IN ('EVALUATION_REQUESTED','EVALUATING') AND d.evaluation_due_at IS NULL)
                    OR
                    (d.stage NOT IN ('EVALUATION_REQUESTED','EVALUATING')
                     AND (btrim(d.next_action) = '' OR d.next_action_due_at IS NULL))
               )
            ON CONFLICT (organization_id, issue_type, source_key) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                deal_case_id = EXCLUDED.deal_case_id,
                source_name = EXCLUDED.source_name,
                summary = EXCLUDED.summary,
                impact = EXCLUDED.impact,
                recommended_subject = EXCLUDED.recommended_subject,
                status = CASE WHEN data_quality_issue.status = 'RESOLVED'
                              THEN CASE WHEN data_quality_issue.assigned_subject IS NULL THEN 'OPEN' ELSE 'ASSIGNED' END
                              ELSE data_quality_issue.status END,
                resolved_at = CASE WHEN data_quality_issue.status = 'RESOLVED' THEN NULL ELSE data_quality_issue.resolved_at END,
                action_reason = CASE WHEN data_quality_issue.status = 'RESOLVED'
                                     THEN 'Reegel tuvastas rikkumise uuesti või jätkuvalt.'
                                     ELSE data_quality_issue.action_reason END,
                last_detected_at = EXCLUDED.last_detected_at,
                last_scan_id = EXCLUDED.last_scan_id,
                updated_by_subject = 'system-data-quality',
                updated_at = EXCLUDED.updated_at
            """, actor, now, now, scanId, now, now, orgId, ACTIVE_DEAL_STAGES.toArray(String[]::new));
    }

    private Record requireIssue(UUID orgId, UUID issueId, boolean lock) {
        String sql = "SELECT * FROM data_quality_issue WHERE organization_id = ? AND id = ?" + (lock ? " FOR UPDATE" : "");
        Record row = dsl.fetchOne(sql, orgId, issueId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Andmekvaliteedi probleemi ei leitud.");
        return row;
    }

    private DataQualityItem get(UUID orgId, UUID issueId) {
        return item(issueWithOwner(orgId, issueId));
    }

    private Record issueWithOwner(UUID orgId, UUID issueId) {
        Record row = dsl.fetchOne("""
            SELECT q.id, q.owner_id, o.display_name AS owner_name, q.deal_case_id,
                   q.issue_type, q.source_name, q.source_key, q.summary, q.impact,
                   q.recommended_subject, q.assigned_subject, q.status,
                   q.first_detected_at, q.last_detected_at, q.resolved_at,
                   q.action_reason, q.updated_by_subject, q.updated_at
              FROM data_quality_issue q
              JOIN owner o ON o.organization_id = q.organization_id AND o.id = q.owner_id
             WHERE q.organization_id = ? AND q.id = ?
            """, orgId, issueId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Andmekvaliteedi probleemi ei leitud.");
        return row;
    }

    private DataQualityItem item(Record row) {
        return new DataQualityItem(
            row.get("id", UUID.class), row.get("owner_id", UUID.class), row.get("owner_name", String.class),
            row.get("deal_case_id", UUID.class), row.get("issue_type", String.class), row.get("source_name", String.class),
            row.get("source_key", String.class), row.get("summary", String.class), row.get("impact", String.class),
            row.get("recommended_subject", String.class), row.get("assigned_subject", String.class),
            row.get("status", String.class), instant(row, "first_detected_at"), instant(row, "last_detected_at"),
            instant(row, "resolved_at"), row.get("action_reason", String.class),
            row.get("updated_by_subject", String.class), instant(row, "updated_at"));
    }

    private JsonNode json(JSONB value) {
        try {
            return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value.data());
        } catch (Exception exception) {
            throw new IllegalStateException("Andmekvaliteedi auditi JSON-i ei õnnestunud lugeda.", exception);
        }
    }

    private static void appendEquals(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" = ? ");
        params.add(value);
    }

    private static String normalize(String value, Set<String> allowed, String label) {
        String normalized = blank(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Tundmatu " + label + ".");
        }
        return normalized;
    }

    private static String requiredReason(ActionRequest request, String message) {
        String reason = request == null ? null : blank(request.reason());
        if (reason == null) throw new ResponseStatusException(BAD_REQUEST, message);
        if (reason.length() > 2000) throw new ResponseStatusException(BAD_REQUEST, "Põhjendus on liiga pikk.");
        return reason;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long longValue(Record row, String field) {
        Long value = row == null ? null : row.get(field, Long.class);
        return value == null ? 0L : value;
    }

    public record ScanSummary(UUID scanId, int detectedCount, int autoResolvedCount, Instant finishedAt) {}
    public record QueueResponse(List<DataQualityItem> items, long total, long openCount,
                                long assignedCount, long resolvedCount, long criticalCount) {}
    public record DataQualityItem(UUID id, UUID ownerId, String ownerName, UUID dealId,
                                  String issueType, String sourceName, String sourceKey, String summary,
                                  String impact, String recommendedSubject, String assignedSubject,
                                  String status, Instant firstDetectedAt, Instant lastDetectedAt,
                                  Instant resolvedAt, String actionReason, String updatedBy, Instant updatedAt) {}
    public record DataQualityEvent(UUID id, String eventType, String fromStatus, String toStatus,
                                   String reason, String actorSubject, JsonNode snapshot, Instant createdAt) {}
    public record AssignRequest(String assigneeSubject, String reason) {}
    public record ActionRequest(String reason) {}
}
