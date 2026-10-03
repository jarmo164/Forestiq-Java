package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.OWNER_ACTIVITY;
import static ee.forestiq.jooq.Tables.USER_STATUS_CHANGE_RECORD;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.Activity;

@Repository
public class OwnerActivityRepository {

    private final DSLContext dsl;

    public OwnerActivityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Activity> findActivities(UUID orgId, UUID ownerId) {
        return dsl.select(OWNER_ACTIVITY.ID, OWNER_ACTIVITY.ACTIVITY_TYPE, OWNER_ACTIVITY.CONTENT,
                OWNER_ACTIVITY.CREATED_AT, OWNER_ACTIVITY.CREATED_BY_SUBJECT)
            .from(OWNER_ACTIVITY)
            .where(OWNER_ACTIVITY.ORGANIZATION_ID.eq(orgId).and(OWNER_ACTIVITY.OWNER_ID.eq(ownerId)))
            .orderBy(OWNER_ACTIVITY.CREATED_AT.desc())
            .fetch(record -> new Activity(
                record.value1(),
                record.value2(),
                record.value3(),
                record.value4().toInstant(),
                record.value5()));
    }

    public void addActivity(UUID orgId, UUID ownerId, String type, String content, String subject) {
        dsl.insertInto(OWNER_ACTIVITY)
            .columns(OWNER_ACTIVITY.ID, OWNER_ACTIVITY.ORGANIZATION_ID, OWNER_ACTIVITY.OWNER_ID,
                OWNER_ACTIVITY.ACTIVITY_TYPE, OWNER_ACTIVITY.CONTENT, OWNER_ACTIVITY.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), orgId, ownerId, type, content, subject)
            .execute();
    }

    /**
     * Persists the complete V44 contact activity shape and returns its durable
     * identifier for the workflow response and related deal event.
     *
     * <p>The generated jOOQ metadata deliberately remains compatible with
     * earlier migrations, so the V44 columns are addressed by this owner-owned
     * SQL adapter instead of leaking the persistence detail to workflow.</p>
     */
    public UUID addWorkflowContact(UUID orgId, UUID ownerId, String activityType, String content, String subject,
                                   String contactChannel, String outcomeCode, String reason,
                                   UUID dealCaseId, Long relatedReminderId) {
        return dsl.fetchOne("""
            INSERT INTO owner_activity
                (organization_id, owner_id, activity_type, content, created_by_subject,
                 source_type, contact_channel, outcome_code, reason, deal_case_id, related_reminder_id)
            VALUES (?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?)
            RETURNING id
            """, orgId, ownerId, activityType, content, subject,
            contactChannel, outcomeCode, reason, dealCaseId, relatedReminderId)
            .get("id", UUID.class);
    }

    /** Persists an owner timeline entry caused by a workflow-owned reminder mutation. */
    public void addWorkflowReminderLifecycle(UUID orgId, UUID ownerId, String content, String subject,
                                             UUID dealCaseId, Long relatedReminderId) {
        dsl.execute("""
            INSERT INTO owner_activity
                (organization_id, owner_id, activity_type, content, created_by_subject,
                 source_type, deal_case_id, related_reminder_id)
            VALUES (?, ?, 'REMINDER', ?, ?, 'USER', ?, ?)
            """, orgId, ownerId, content, subject, dealCaseId, relatedReminderId);
    }

    public List<WorkflowHistoryRow> findWorkflowHistory(
        UUID orgId,
        UUID ownerId,
        String activityType,
        String outcome,
        String author,
        Instant from,
        Instant to,
        Instant cursorCreatedAt,
        UUID cursorId,
        int fetchLimit) {

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT id, activity_type, source_type, content, contact_channel, outcome_code, reason,
                   created_by_subject, created_at, related_reminder_id, deal_case_id
              FROM owner_activity
             WHERE organization_id = ? AND owner_id = ?
            """);
        params.add(orgId);
        params.add(ownerId);
        if (activityType != null) {
            sql.append(" AND activity_type = ?");
            params.add(activityType);
        }
        if (outcome != null) {
            sql.append(" AND outcome_code = ?");
            params.add(outcome);
        }
        if (author != null) {
            sql.append(" AND created_by_subject = ?");
            params.add(author);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(utc(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            params.add(utc(to));
        }
        if (cursorCreatedAt != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(utc(cursorCreatedAt));
            params.add(cursorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(fetchLimit);

        return dsl.fetch(sql.toString(), params.toArray()).map(this::workflowHistoryRow);
    }

    private WorkflowHistoryRow workflowHistoryRow(Record row) {
        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        return new WorkflowHistoryRow(
            row.get("id", UUID.class),
            row.get("activity_type", String.class),
            row.get("source_type", String.class),
            row.get("content", String.class),
            row.get("contact_channel", String.class),
            row.get("outcome_code", String.class),
            row.get("reason", String.class),
            row.get("created_by_subject", String.class),
            createdAt == null ? null : createdAt.toInstant(),
            row.get("related_reminder_id", Long.class),
            row.get("deal_case_id", UUID.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record WorkflowHistoryRow(
        UUID id,
        String activityType,
        String sourceType,
        String content,
        String contactChannel,
        String outcomeCode,
        String reason,
        String createdBySubject,
        Instant createdAt,
        Long relatedReminderId,
        UUID dealCaseId) {
    }

    /** Persists a status transition for organization-scoped user activity statistics. */
    public void addUserStatusChangeRecord(UUID orgId, String userSubject, String fromStatus, String toStatus) {
        dsl.insertInto(USER_STATUS_CHANGE_RECORD)
            .columns(USER_STATUS_CHANGE_RECORD.ORGANIZATION_ID, USER_STATUS_CHANGE_RECORD.USER_SUBJECT,
                USER_STATUS_CHANGE_RECORD.FROM_STATUS, USER_STATUS_CHANGE_RECORD.TO_STATUS)
            .values(orgId, userSubject, fromStatus, toStatus)
            .execute();
    }
}
