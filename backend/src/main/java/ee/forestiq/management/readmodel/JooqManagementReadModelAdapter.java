package ee.forestiq.management.readmodel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.management.SalesManagementDtos.SegmentRules;

/**
 * jOOQ read-model adapter for management. Cross-domain SQL is intentionally
 * isolated here and must remain SELECT-only.
 */
@Component
@Transactional(readOnly = true)
class JooqManagementReadModelAdapter implements ManagementReadModelPort {
    private final DSLContext dsl;

    JooqManagementReadModelAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public AssignmentLoad assignmentLoad(UUID orgId, String subject) {
        Record counts = dsl.fetchOne("""
            SELECT
                (SELECT count(*) FROM owner o WHERE o.organization_id = ? AND o.assigned_to_subject = ?) AS assigned_owners,
                (SELECT count(*) FROM reminder r WHERE r.organization_id = ? AND r.assignee_subject = ? AND r.status = 'OPEN') AS open_work,
                (SELECT count(*) FROM reminder r WHERE r.organization_id = ? AND r.assignee_subject = ? AND r.status = 'OPEN' AND r.due_time < now()) AS overdue_work,
                (SELECT count(*) FROM reminder r WHERE r.organization_id = ? AND r.assignee_subject = ? AND r.status = 'OPEN' AND r.due_time >= now() AND r.due_time < now() + interval '7 days') AS due_next_7,
                (SELECT count(*) FROM management_assignment_item i WHERE i.organization_id = ? AND i.to_subject = ? AND i.created_at >= now() - interval '7 days') AS recent_assigned
            """, orgId, subject, orgId, subject, orgId, subject, orgId, subject, orgId, subject);
        return new AssignmentLoad(
            number(counts, "assigned_owners"),
            number(counts, "open_work"),
            number(counts, "overdue_work"),
            number(counts, "due_next_7"),
            number(counts, "recent_assigned"));
    }

    @Override
    public SegmentSlice segmentMembers(UUID orgId, SegmentRules rules, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT o.id, o.display_name, o.status, o.assigned_to_subject,
                   COALESCE((SELECT SUM(p.forest_area)
                     FROM owner_parcel_relation r JOIN land_parcel p
                       ON p.organization_id=r.organization_id AND p.id=r.parcel_id
                    WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE), 0) AS forest_area,
                   (SELECT MIN(p.county)
                     FROM owner_parcel_relation r JOIN land_parcel p
                       ON p.organization_id=r.organization_id AND p.id=r.parcel_id
                    WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE) AS county,
                   (o.phone IS NOT NULL AND btrim(o.phone) <> '') OR (o.email IS NOT NULL AND btrim(o.email) <> '') AS has_contact
              FROM owner o
             WHERE o.organization_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(orgId);

        if (rules.statuses() != null && !rules.statuses().isEmpty()) {
            List<String> statuses = rules.statuses().stream()
                .map(String::trim).filter(v -> !v.isBlank()).distinct().limit(30).toList();
            sql.append(" AND o.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
                params.add(statuses.get(i));
            }
            sql.append(')');
        }
        String county = trim(rules.county());
        if (county != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM owner_parcel_relation r JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE AND lower(p.county)=lower(?))");
            params.add(county);
        }
        if (rules.minForestAreaHectares() != null) {
            sql.append(" AND COALESCE((SELECT SUM(p.forest_area) FROM owner_parcel_relation r JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE),0) >= ?");
            params.add(rules.minForestAreaHectares());
        }
        String contact = normalize(rules.contactState());
        if ("HAS_CONTACT".equals(contact)) sql.append(" AND ((o.phone IS NOT NULL AND btrim(o.phone)<>'') OR (o.email IS NOT NULL AND btrim(o.email)<>''))");
        if ("MISSING_CONTACT".equals(contact)) sql.append(" AND NOT ((o.phone IS NOT NULL AND btrim(o.phone)<>'') OR (o.email IS NOT NULL AND btrim(o.email)<>''))");

        String signal = normalize(rules.signalCode());
        if ("MISSING_CONTACT".equals(signal)) {
            sql.append(" AND NOT ((o.phone IS NOT NULL AND btrim(o.phone)<>'') OR (o.email IS NOT NULL AND btrim(o.email)<>''))");
        } else if ("FRESH_FOREST_NOTIFICATION".equals(signal)) {
            sql.append(" AND EXISTS (SELECT 1 FROM owner_parcel_relation r JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id JOIN cadastre_notification n ON n.organization_id=p.organization_id AND n.cadastral_code=p.cadastral_code WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE AND n.archived=FALSE AND n.created_at >= now()-interval '30 days')");
        } else if ("RESTRICTION_LABEL".equals(signal)) {
            sql.append(" AND EXISTS (SELECT 1 FROM owner_parcel_relation r JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id JOIN cadastre_label l ON l.organization_id=p.organization_id AND l.cadastral_code=p.cadastral_code WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE AND (lower(l.label) LIKE '%kitsendus%' OR lower(l.label) LIKE '%restriction%' OR lower(l.label) LIKE '%piirang%'))");
        } else if ("STALE_REGISTRY_DATA".equals(signal)) {
            sql.append(" AND EXISTS (SELECT 1 FROM owner_parcel_relation r JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id WHERE r.organization_id=o.organization_id AND r.owner_id=o.id AND r.active=TRUE AND GREATEST((SELECT MAX(f.created_at) FROM forest_registry_feature f WHERE f.organization_id=p.organization_id AND f.cadastral_code=p.cadastral_code),(SELECT MAX(n.created_at) FROM cadastre_notification n WHERE n.organization_id=p.organization_id AND n.cadastral_code=p.cadastral_code)) IS NOT NULL AND GREATEST((SELECT MAX(f.created_at) FROM forest_registry_feature f WHERE f.organization_id=p.organization_id AND f.cadastral_code=p.cadastral_code),(SELECT MAX(n.created_at) FROM cadastre_notification n WHERE n.organization_id=p.organization_id AND n.cadastral_code=p.cadastral_code)) < now()-interval '7 days')");
        }
        sql.append(" ORDER BY o.updated_at DESC, o.id");

        List<SegmentRow> all = dsl.fetch(sql.toString(), params.toArray()).map(row -> new SegmentRow(
            row.get("id", UUID.class),
            row.get("display_name", String.class),
            row.get("status", String.class),
            row.get("assigned_to_subject", String.class),
            row.get("forest_area", BigDecimal.class),
            row.get("county", String.class),
            Boolean.TRUE.equals(row.get("has_contact", Boolean.class))));
        return new SegmentSlice(all.size(), all.stream().limit(limit).toList());
    }

    private static long number(Record row, String field) {
        Number value = row == null ? null : row.get(field, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }
}
