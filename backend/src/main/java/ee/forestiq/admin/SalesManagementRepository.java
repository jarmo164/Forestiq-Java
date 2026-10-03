package ee.forestiq.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.admin.SalesManagementController.InterventionItem;
import ee.forestiq.admin.SalesManagementController.SalesManagementOverview;
import ee.forestiq.admin.SalesManagementController.TeamMemberPerformance;

@Repository
public class SalesManagementRepository {

    private final DSLContext dsl;

    public SalesManagementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public SalesManagementOverview overview(UUID orgId) {
        var ownerSummary = dsl.fetchOne("""
            SELECT COUNT(*) AS total_owners,
                   COUNT(*) FILTER (WHERE status = 'NEW' AND assigned_to_subject IS NULL) AS unassigned_leads,
                   COUNT(*) FILTER (WHERE status IN ('CONTACTED', 'INTERESTED')
                     AND NOT EXISTS (
                       SELECT 1 FROM owner_activity a
                       WHERE a.organization_id = owner.organization_id AND a.owner_id = owner.id
                         AND a.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                     )) AS stale_opportunities,
                   COUNT(*) FILTER (WHERE assigned_to_subject IS NOT NULL) AS assigned_owners
            FROM owner WHERE organization_id = ?
            """, orgId);
        long overdueReminders = dsl.fetchOne("""
            SELECT COUNT(*) FROM reminder
            WHERE organization_id = ? AND due_time < CURRENT_TIMESTAMP
            """, orgId).get(0, Long.class);
        long salesActivitiesLast7Days = dsl.fetchOne("""
            SELECT COUNT(*) FROM owner_activity
            WHERE organization_id = ? AND created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
            """, orgId).get(0, Long.class);

        List<TeamMemberPerformance> team = dsl.fetch("""
            WITH team_subjects AS (
                SELECT DISTINCT assigned_to_subject AS subject FROM owner WHERE organization_id = ? AND assigned_to_subject IS NOT NULL
                UNION
                SELECT DISTINCT created_by_subject AS subject FROM owner_activity WHERE organization_id = ?
                UNION
                SELECT DISTINCT creator AS subject FROM reminder WHERE organization_id = ?
                UNION
                SELECT DISTINCT user_subject AS subject FROM user_status_change_record WHERE organization_id = ?
            )
            SELECT subject,
                (SELECT COUNT(*) FROM owner o WHERE o.organization_id = ? AND o.assigned_to_subject = team_subjects.subject) AS assigned_owners,
                (SELECT COUNT(*) FROM owner o WHERE o.organization_id = ? AND o.assigned_to_subject = team_subjects.subject AND o.status IN ('CONTACTED', 'INTERESTED', 'EVALUATION', 'WAITS_FOR_EVALUATION')) AS active_opportunities,
                (SELECT COUNT(*) FROM user_status_change_record u WHERE u.organization_id = ? AND u.user_subject = team_subjects.subject AND u.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days') AS status_changes_last_7_days,
                (SELECT COUNT(*) FROM reminder r WHERE r.organization_id = ? AND r.creator = team_subjects.subject AND r.due_time < CURRENT_TIMESTAMP) AS overdue_reminders,
                (SELECT MAX(a.created_at) FROM owner_activity a WHERE a.organization_id = ? AND a.created_by_subject = team_subjects.subject) AS last_activity_at
            FROM team_subjects
            ORDER BY assigned_owners DESC, subject ASC
            """, orgId, orgId, orgId, orgId, orgId, orgId, orgId, orgId, orgId)
            .map(record -> new TeamMemberPerformance(
                record.get("subject", String.class), record.get("assigned_owners", Long.class),
                record.get("active_opportunities", Long.class), record.get("status_changes_last_7_days", Long.class),
                record.get("overdue_reminders", Long.class), record.get("last_activity_at", Instant.class)));

        List<InterventionItem> interventions = dsl.fetch("""
            SELECT id, display_name, status, assigned_to_subject, updated_at, 'UNASSIGNED_LEAD' AS reason
            FROM owner
            WHERE organization_id = ? AND status = 'NEW' AND assigned_to_subject IS NULL
            UNION ALL
            SELECT o.id, o.display_name, o.status, o.assigned_to_subject, COALESCE(MAX(a.created_at), o.updated_at) AS updated_at, 'STALE_OPPORTUNITY' AS reason
            FROM owner o
            LEFT JOIN owner_activity a ON a.organization_id = o.organization_id AND a.owner_id = o.id
            WHERE o.organization_id = ? AND o.status IN ('CONTACTED', 'INTERESTED')
            GROUP BY o.id, o.display_name, o.status, o.assigned_to_subject, o.updated_at
            HAVING MAX(a.created_at) IS NULL OR MAX(a.created_at) < CURRENT_TIMESTAMP - INTERVAL '7 days'
            ORDER BY updated_at ASC NULLS FIRST
            LIMIT 25
            """, orgId, orgId)
            .map(record -> new InterventionItem(
                record.get("id", UUID.class), record.get("display_name", String.class), record.get("status", String.class),
                record.get("assigned_to_subject", String.class), record.get("updated_at", Instant.class), record.get("reason", String.class)));

        return new SalesManagementOverview(
            ownerSummary.get("total_owners", Long.class), ownerSummary.get("assigned_owners", Long.class),
            ownerSummary.get("unassigned_leads", Long.class), ownerSummary.get("stale_opportunities", Long.class),
            overdueReminders, salesActivitiesLast7Days, team, interventions);
    }
}
