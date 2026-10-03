package ee.forestiq.sales;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.owner.OwnerDtos.CreateReminderRequest;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.reminder.ReminderService;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.time.BusinessCalendar;

import static org.jooq.impl.DSL.*;

/**
 * Personal selling workflow. It turns CRM data into the next best contact and
 * makes common call outcomes transactional so a salesperson does not have to
 * perform separate administrative steps after every conversation.
 *
 * Evaluation is intentionally not a generic call outcome. The only supported
 * transition into evaluation is deal qualification, which creates the deal,
 * selected parcels, evaluator task, SLA and audit trail atomically.
 */
@Service
public class SalesWorkspaceService {

    private static final LocalTime FOLLOW_UP_TIME = LocalTime.of(10, 0);

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final OwnerService ownerService;
    private final ReminderService reminderService;
    private final BusinessCalendar businessCalendar;

    public SalesWorkspaceService(DSLContext dsl, OrganizationContext organizationContext,
                                 OwnerService ownerService, ReminderService reminderService,
                                 BusinessCalendar businessCalendar) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.ownerService = ownerService;
        this.reminderService = reminderService;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public List<SalesTask> priorityQueue() {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        return dsl.fetch("""
            SELECT o.id, o.display_name, o.personal_or_registry_code, o.status, o.phone, o.updated_at,
                   MIN(r.due_time) AS next_reminder_at,
                   MAX(a.created_at) AS last_activity_at,
                   CASE
                     WHEN EXISTS (SELECT 1 FROM reminder overdue WHERE overdue.organization_id = o.organization_id
                         AND overdue.owner_id = o.id AND overdue.creator = ? AND overdue.due_time < CURRENT_TIMESTAMP)
                       THEN 'OVERDUE_FOLLOW_UP'
                     WHEN EXISTS (SELECT 1 FROM reminder today WHERE today.organization_id = o.organization_id
                         AND today.owner_id = o.id AND today.creator = ?
                         AND today.due_time >= date_trunc('day', CURRENT_TIMESTAMP)
                         AND today.due_time < date_trunc('day', CURRENT_TIMESTAMP) + INTERVAL '1 day')
                       THEN 'DUE_TODAY'
                     WHEN o.status IN ('NEW', 'ASSIGNED') AND NOT EXISTS (SELECT 1 FROM owner_activity first_contact
                         WHERE first_contact.organization_id = o.organization_id AND first_contact.owner_id = o.id)
                       THEN 'FIRST_CONTACT'
                     WHEN o.status IN ('CONTACTED', 'INTERESTED') AND NOT EXISTS (SELECT 1 FROM owner_activity recent_activity
                         WHERE recent_activity.organization_id = o.organization_id AND recent_activity.owner_id = o.id
                         AND recent_activity.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days')
                       THEN 'STALE_OPPORTUNITY'
                     ELSE 'ACTIVE_FOLLOW_UP'
                   END AS priority_reason,
                   CASE
                     WHEN EXISTS (SELECT 1 FROM reminder overdue WHERE overdue.organization_id = o.organization_id
                         AND overdue.owner_id = o.id AND overdue.creator = ? AND overdue.due_time < CURRENT_TIMESTAMP) THEN 100
                     WHEN EXISTS (SELECT 1 FROM reminder today WHERE today.organization_id = o.organization_id
                         AND today.owner_id = o.id AND today.creator = ?
                         AND today.due_time < date_trunc('day', CURRENT_TIMESTAMP) + INTERVAL '1 day') THEN 90
                     WHEN o.status IN ('NEW', 'ASSIGNED') AND NOT EXISTS (SELECT 1 FROM owner_activity first_contact
                         WHERE first_contact.organization_id = o.organization_id AND first_contact.owner_id = o.id) THEN 80
                     WHEN o.status IN ('CONTACTED', 'INTERESTED') AND NOT EXISTS (SELECT 1 FROM owner_activity recent_activity
                         WHERE recent_activity.organization_id = o.organization_id AND recent_activity.owner_id = o.id
                         AND recent_activity.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days') THEN 70
                     ELSE 50
                   END AS priority_score
            FROM owner o
            LEFT JOIN reminder r ON r.organization_id = o.organization_id AND r.owner_id = o.id AND r.creator = ?
            LEFT JOIN owner_activity a ON a.organization_id = o.organization_id AND a.owner_id = o.id
            WHERE o.organization_id = ? AND o.assigned_to_subject = ?
              AND o.status NOT IN ('CONTRACTED', 'NOT_INTERESTED', 'DECEASED')
            GROUP BY o.id, o.display_name, o.personal_or_registry_code, o.status, o.phone, o.updated_at, o.organization_id
            ORDER BY priority_score DESC, next_reminder_at ASC NULLS LAST, last_activity_at ASC NULLS FIRST, o.id ASC
            LIMIT 40
            """, subject, subject, subject, subject, subject, orgId, subject)
            .map(record -> new SalesTask(
                record.get("id", UUID.class), record.get("display_name", String.class),
                record.get("personal_or_registry_code", String.class), record.get("status", String.class),
                record.get("phone", String.class), record.get("priority_reason", String.class),
                record.get("priority_score", Integer.class), record.get("next_reminder_at", Instant.class),
                record.get("last_activity_at", Instant.class), record.get("updated_at", Instant.class)));
    }

    @Transactional
    public OutcomeResult applyOutcome(UUID ownerId, SalesOutcome outcome) {
        return applyOutcome(ownerId, outcome, null);
    }

    @Transactional
    public OutcomeResult applyOutcome(UUID ownerId, SalesOutcome outcome, String reason) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String currentStatus = ensureAssignedToCurrentSeller(orgId, ownerId);
        return switch (outcome) {
            case NO_ANSWER -> contactAndSchedule(ownerId, currentStatus, "Kontakt katsetatud — vastust ei saadud.", "Proovi uuesti ühendust võtta", nextBusinessMorning(2));
            case CALL_BACK -> contactAndSchedule(ownerId, currentStatus, "Klient palus tagasi helistada.", "Helista kliendile tagasi", nextBusinessMorning(1));
            case INTERESTED -> interestedAndSchedule(ownerId, currentStatus);
            case NOT_INTERESTED -> notInterested(ownerId, currentStatus, reason);
        };
    }

    private OutcomeResult notInterested(UUID ownerId, String currentStatus, String reason) {
        String normalizedReason = reason == null ? null : reason.trim();
        if (normalizedReason == null || normalizedReason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tulemuse „Ei huvita” põhjendus on kohustuslik.");
        }
        return changeStatus(ownerId, currentStatus, "NOT_INTERESTED", "Klient ei ole hetkel huvitatud. Põhjus: " + normalizedReason);
    }

    private OutcomeResult contactAndSchedule(UUID ownerId, String currentStatus, String note, String reminderText, Instant dueAt) {
        if (currentStatus.equals("NEW") || currentStatus.equals("ASSIGNED")) {
            ownerService.changeStatus(ownerId, new ChangeStatusRequest("CONTACTED", note, null));
            currentStatus = "CONTACTED";
        } else {
            ownerService.addNote(ownerId, note);
        }
        reminderService.create(new CreateReminderRequest(reminderText, dueAt, ownerId, null));
        return new OutcomeResult(currentStatus, dueAt, reminderText);
    }

    private OutcomeResult interestedAndSchedule(UUID ownerId, String currentStatus) {
        String note = "Klient on huvitatud; järgmine kontakt hindamise ja pakkumise ettevalmistamiseks.";
        Instant dueAt = nextBusinessMorning(1);
        if (currentStatus.equals("NEW") || currentStatus.equals("ASSIGNED") || currentStatus.equals("CONTACTED")) {
            ownerService.changeStatus(ownerId, new ChangeStatusRequest("INTERESTED", note, null));
            currentStatus = "INTERESTED";
        } else {
            ownerService.addNote(ownerId, note);
        }
        reminderService.create(new CreateReminderRequest("Lepi kokku hindamise järgmine samm", dueAt, ownerId, null));
        return new OutcomeResult(currentStatus, dueAt, "Lepi kokku hindamise järgmine samm");
    }

    private OutcomeResult changeStatus(UUID ownerId, String currentStatus, String status, String note) {
        if (currentStatus.equals(status)) {
            ownerService.addNote(ownerId, note);
        } else {
            ownerService.changeStatus(ownerId, new ChangeStatusRequest(status, note, null));
        }
        return new OutcomeResult(status, null, null);
    }

    private String ensureAssignedToCurrentSeller(UUID orgId, UUID ownerId) {
        String status = dsl.select(field(name("status"), String.class)).from(table("owner"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("id"), UUID.class).eq(ownerId))
                .and(field(name("assigned_to_subject"), String.class).eq(organizationContext.subject())))
            .fetchOne(0, String.class);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner is not assigned to the current seller");
        }
        return status;
    }

    private Instant nextBusinessMorning(int businessDaysFromNow) {
        return businessCalendar.nextBusinessTime(Instant.now(), businessDaysFromNow, FOLLOW_UP_TIME);
    }

    public enum SalesOutcome { NO_ANSWER, CALL_BACK, INTERESTED, NOT_INTERESTED }

    public record SalesTask(
        UUID ownerId, String ownerName, String personalOrRegistryCode, String status, String phone,
        String priorityReason, int priorityScore, Instant nextReminderAt, Instant lastActivityAt, Instant updatedAt
    ) { }

    public record OutcomeResult(String status, Instant nextActionAt, String nextAction) { }
}
