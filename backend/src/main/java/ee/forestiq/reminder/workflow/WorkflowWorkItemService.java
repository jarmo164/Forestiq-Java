package ee.forestiq.reminder.workflow;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/** PostgreSQL adapter that owns workflow-facing reminder and reminder-event persistence. */
@Service
public class WorkflowWorkItemService implements WorkflowWorkItemPort {

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;

    public WorkflowWorkItemService(DSLContext dsl, OrganizationContext organizationContext) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
    }

    @Override
    @Transactional
    public WorkItem requireOpen(long workItemId) {
        Record row = dsl.fetchOne("""
            SELECT id, owner_id, deal_case_id, assignee_subject, due_time,
                   reminder_text, activity_type, priority_reason
            FROM reminder
            WHERE organization_id = ? AND id = ? AND status = 'OPEN'
            FOR UPDATE
            """, organizationContext.requiredOrganizationId(), workItemId);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "Aktiivset tegevust ei leitud.");
        }
        return mapWorkItem(row);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItem> listOpenForOwner(UUID ownerId) {
        return dsl.fetch("""
            SELECT id, owner_id, deal_case_id, assignee_subject, due_time,
                   reminder_text, activity_type, priority_reason
            FROM reminder
            WHERE organization_id = ? AND owner_id = ? AND status = 'OPEN'
            ORDER BY due_time, id
            LIMIT 100
            """, organizationContext.requiredOrganizationId(), ownerId)
            .map(this::mapWorkItem);
    }

    @Override
    @Transactional
    public long create(CreateCommand command) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        String actor = organizationContext.subject();
        Record created = dsl.fetchOne("""
            INSERT INTO reminder
                (organization_id, creator, owner_id, reminder_text, due_time, assignee_subject,
                 status, activity_type, priority_reason, deal_case_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, now())
            RETURNING id
            """, organizationId, actor, command.ownerId(), command.text(), dbTime(command.dueTime()),
            command.assigneeSubject(), command.activityType(), command.priorityReason(), command.dealId());
        if (created == null) {
            throw new IllegalStateException("Järgmist tegevust ei loodud.");
        }
        Long id = created.get("id", Long.class);
        insertEvent(organizationId, id, command.ownerId(), command.dealId(), "CREATED",
            null, command.dueTime(), null, command.assigneeSubject(),
            "Järgmine tegevus: " + command.text(), actor);
        return id;
    }

    @Override
    @Transactional
    public void complete(WorkItem workItem, String note) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        int changed = dsl.execute("""
            UPDATE reminder
               SET status = 'COMPLETED', completed_at = now(), completion_note = ?, updated_at = now()
             WHERE organization_id = ? AND id = ? AND status = 'OPEN'
            """, trimToNull(note), organizationId, workItem.id());
        assertChanged(changed);
        insertEvent(organizationId, workItem.id(), workItem.ownerId(), workItem.dealId(), "COMPLETED",
            workItem.dueTime(), null, workItem.assigneeSubject(), null, trimToNull(note),
            organizationContext.subject());
    }

    @Override
    @Transactional
    public void postpone(WorkItem workItem, Instant newDueTime, String reason) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        int changed = dsl.execute("""
            UPDATE reminder
               SET due_time = ?, updated_at = now()
             WHERE organization_id = ? AND id = ? AND status = 'OPEN'
            """, dbTime(newDueTime), organizationId, workItem.id());
        assertChanged(changed);
        insertEvent(organizationId, workItem.id(), workItem.ownerId(), workItem.dealId(), "POSTPONED",
            workItem.dueTime(), newDueTime, workItem.assigneeSubject(), null, trimToNull(reason),
            organizationContext.subject());
    }

    @Override
    @Transactional
    public void delegate(WorkItem workItem, String newAssigneeSubject, String reason) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        int changed = dsl.execute("""
            UPDATE reminder
               SET assignee_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ? AND status = 'OPEN'
            """, newAssigneeSubject, organizationId, workItem.id());
        assertChanged(changed);
        insertEvent(organizationId, workItem.id(), workItem.ownerId(), workItem.dealId(), "DELEGATED",
            workItem.dueTime(), null, workItem.assigneeSubject(), newAssigneeSubject, trimToNull(reason),
            organizationContext.subject());
    }

    @Override
    @Transactional(readOnly = true)
    public QueueSnapshot queue(QueueView view, QueueWindow window) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        String predicate = switch (view) {
            case TODAY -> "r.due_time >= CAST(? AS timestamp with time zone) AND r.due_time < CAST(? AS timestamp with time zone)";
            case WEEK -> "r.due_time >= CAST(? AS timestamp with time zone) AND r.due_time < CAST(? AS timestamp with time zone)";
            case OVERDUE -> "r.due_time < CAST(? AS timestamp with time zone)";
        };

        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.add(subject);
        if (view == QueueView.TODAY) {
            params.add(dbTime(window.todayStart()));
            params.add(dbTime(window.tomorrowStart()));
        } else if (view == QueueView.WEEK) {
            params.add(dbTime(window.todayStart()));
            params.add(dbTime(window.sevenDaysEnd()));
        } else {
            params.add(dbTime(window.now()));
        }

        String sql = """
            SELECT r.id, r.owner_id, o.display_name AS owner_name, r.deal_case_id,
                   CASE WHEN d.id IS NULL THEN NULL ELSE concat(d.sale_subject, ' / ', d.stage) END AS related_deal,
                   r.cadastral_code, r.property_name, r.activity_type, r.due_time, r.priority_reason,
                   r.assignee_subject,
                   EXISTS (
                       SELECT 1 FROM reminder_event re
                       WHERE re.organization_id = r.organization_id AND re.reminder_id = r.id
                         AND re.event_type = 'DELEGATED' AND re.to_assignee = r.assignee_subject
                   ) AS delegated
            FROM reminder r
            LEFT JOIN owner o ON o.organization_id = r.organization_id AND o.id = r.owner_id
            LEFT JOIN deal_case d ON d.organization_id = r.organization_id AND d.id = r.deal_case_id
            WHERE r.organization_id = ? AND r.assignee_subject = ? AND r.status = 'OPEN' AND""" + " " + predicate
            + " ORDER BY r.due_time, r.id";

        List<QueueItem> items = dsl.fetch(sql, params.toArray()).map(row -> {
            Instant dueTime = toInstant(row, "due_time");
            return new QueueItem(
                row.get("id", Long.class),
                row.get("owner_id", UUID.class),
                row.get("owner_name", String.class),
                row.get("deal_case_id", UUID.class),
                row.get("related_deal", String.class),
                row.get("cadastral_code", String.class),
                row.get("property_name", String.class),
                row.get("activity_type", String.class),
                dueTime,
                priorityReason(row.get("priority_reason", String.class), dueTime, window),
                row.get("assignee_subject", String.class),
                Boolean.TRUE.equals(row.get("delegated", Boolean.class)));
        });

        QueueCounts counts = new QueueCounts(
            countQueue(organizationId, subject,
                "due_time >= CAST(? AS timestamp with time zone) AND due_time < CAST(? AS timestamp with time zone)",
                window.todayStart(), window.tomorrowStart()),
            countQueue(organizationId, subject,
                "due_time >= CAST(? AS timestamp with time zone) AND due_time < CAST(? AS timestamp with time zone)",
                window.todayStart(), window.sevenDaysEnd()),
            countQueue(organizationId, subject, "due_time < CAST(? AS timestamp with time zone)", window.now()));
        return new QueueSnapshot(view, items, counts);
    }

    private WorkItem mapWorkItem(Record row) {
        return new WorkItem(
            row.get("id", Long.class),
            row.get("owner_id", UUID.class),
            row.get("deal_case_id", UUID.class),
            row.get("assignee_subject", String.class),
            toInstant(row, "due_time"),
            row.get("reminder_text", String.class),
            row.get("activity_type", String.class),
            row.get("priority_reason", String.class));
    }

    private void insertEvent(
        UUID organizationId,
        Long workItemId,
        UUID ownerId,
        UUID dealId,
        String eventType,
        Instant oldDueTime,
        Instant newDueTime,
        String fromAssignee,
        String toAssignee,
        String reason,
        String actor) {
        dsl.execute("""
            INSERT INTO reminder_event
                (organization_id, reminder_id, owner_id, deal_case_id, event_type,
                 old_due_time, new_due_time, from_assignee, to_assignee, reason, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, organizationId, workItemId, ownerId, dealId, eventType,
            dbTime(oldDueTime), dbTime(newDueTime), fromAssignee, toAssignee, reason, actor);
    }

    private long countQueue(UUID organizationId, String subject, String predicate, Instant... times) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.add(subject);
        for (Instant time : times) {
            params.add(dbTime(time));
        }
        Record row = dsl.fetchOne(
            "SELECT count(*) AS c FROM reminder WHERE organization_id = ? AND assignee_subject = ? AND status = 'OPEN' AND " + predicate,
            params.toArray());
        return row == null ? 0L : row.get("c", Long.class);
    }

    private static String priorityReason(String configured, Instant dueTime, QueueWindow window) {
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        if (dueTime != null && dueTime.isBefore(window.now())) {
            return "Üle tähtaja";
        }
        if (dueTime != null && !dueTime.isBefore(window.todayStart()) && dueTime.isBefore(window.tomorrowStart())) {
            return "Tähtaeg täna";
        }
        return "Järgmine tegevus";
    }

    private static void assertChanged(int changed) {
        if (changed != 1) {
            throw new ResponseStatusException(CONFLICT, "Tegevus muutus samal ajal. Laadi vaade uuesti.");
        }
    }

    private static OffsetDateTime dbTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
