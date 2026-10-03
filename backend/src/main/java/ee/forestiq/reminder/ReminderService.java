package ee.forestiq.reminder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.owner.OwnerDtos.CreateReminderRequest;
import ee.forestiq.owner.OwnerDtos.ReminderDto;
import ee.forestiq.security.OrganizationContext;

import static org.jooq.impl.DSL.*;

@Service
public class ReminderService {

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorizationService;

    public ReminderService(DSLContext dsl, OrganizationContext orgCtx,
                           OwnerAuthorizationService ownerAuthorizationService) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorizationService = ownerAuthorizationService;
    }

    @Transactional(readOnly = true)
    public List<ReminderDto> getMyReminders() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String creator = orgCtx.subject();
        return dsl.select(
                field(name("r", "id"), Long.class), field(name("r", "reminder_text"), String.class),
                field(name("r", "due_time"), Instant.class), field(name("r", "created_at"), Instant.class),
                field(name("r", "creator"), String.class), field(name("r", "cadastral_code"), String.class),
                field(name("r", "property_name"), String.class), field(name("r", "owner_id"), UUID.class),
                field(name("o", "display_name"), String.class))
            .from(table("reminder").as("r"))
            .leftJoin(table("owner").as("o")).on(field(name("o", "id"), UUID.class).eq(field(name("r", "owner_id"), UUID.class))
                .and(field(name("o", "organization_id"), UUID.class).eq(field(name("r", "organization_id"), UUID.class))))
            .where(field(name("r", "organization_id"), UUID.class).eq(orgId).and(field(name("r", "creator"), String.class).eq(creator)))
            .orderBy(field(name("r", "due_time")).asc())
            .fetch(r -> new ReminderDto(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9()));
    }

    /** Returns all organization reminders for the administrator dashboard. */
    @Transactional(readOnly = true)
    public List<ReminderDto> getOrganizationReminders() {
        UUID orgId = orgCtx.requiredOrganizationId();
        return dsl.select(
                field(name("r", "id"), Long.class), field(name("r", "reminder_text"), String.class),
                field(name("r", "due_time"), Instant.class), field(name("r", "created_at"), Instant.class),
                field(name("r", "creator"), String.class), field(name("r", "cadastral_code"), String.class),
                field(name("r", "property_name"), String.class), field(name("r", "owner_id"), UUID.class),
                field(name("o", "display_name"), String.class))
            .from(table("reminder").as("r"))
            .leftJoin(table("owner").as("o")).on(field(name("o", "id"), UUID.class).eq(field(name("r", "owner_id"), UUID.class))
                .and(field(name("o", "organization_id"), UUID.class).eq(field(name("r", "organization_id"), UUID.class))))
            .where(field(name("r", "organization_id"), UUID.class).eq(orgId))
            .orderBy(field(name("r", "due_time")).asc())
            .fetch(r -> new ReminderDto(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9()));
    }

    @Transactional
    public void create(CreateReminderRequest req) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String creator = orgCtx.subject();
        if (req.text() == null || req.text().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text required");
        if (req.text().length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text too long");
        if (req.dueTime() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Due time required");

        if (req.ownerId() != null) {
            var owner = dsl.select(field(name("assigned_to_subject"), String.class))
                .from(table("owner"))
                .where(field(name("organization_id"), UUID.class).eq(orgId)
                    .and(field(name("id"), UUID.class).eq(req.ownerId())))
                .fetchOne();
            if (owner == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Owner does not belong to the organization");
            }
            ownerAuthorizationService.assertCanMutateAssignedSubject(owner.value1());
        }

        if (req.cadastralCode() != null && !req.cadastralCode().isBlank()) {
            List<String> assignments = dsl.select(field(name("o", "assigned_to_subject"), String.class))
                .from(table("land_parcel").as("p"))
                .join(table("owner_parcel_relation").as("opr")).on(
                    field(name("opr", "parcel_id"), UUID.class).eq(field(name("p", "id"), UUID.class))
                        .and(field(name("opr", "organization_id"), UUID.class).eq(field(name("p", "organization_id"), UUID.class)))
                        .and(field(name("opr", "active"), Boolean.class).isTrue()))
                .join(table("owner").as("o")).on(
                    field(name("o", "id"), UUID.class).eq(field(name("opr", "owner_id"), UUID.class))
                        .and(field(name("o", "organization_id"), UUID.class).eq(field(name("p", "organization_id"), UUID.class))))
                .where(field(name("p", "organization_id"), UUID.class).eq(orgId)
                    .and(field(name("p", "cadastral_code"), String.class).eq(req.cadastralCode())))
                .fetchInto(String.class);
            if (assignments.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cadastre does not belong to the organization");
            }
            ownerAuthorizationService.assertCanMutateAnyAssignedSubject(assignments);

            if (req.ownerId() != null && !dsl.fetchExists(selectOne()
                .from(table("land_parcel").as("p"))
                .join(table("owner_parcel_relation").as("opr")).on(
                    field(name("opr", "parcel_id"), UUID.class).eq(field(name("p", "id"), UUID.class))
                        .and(field(name("opr", "organization_id"), UUID.class).eq(field(name("p", "organization_id"), UUID.class))))
                .where(field(name("p", "organization_id"), UUID.class).eq(orgId)
                    .and(field(name("p", "cadastral_code"), String.class).eq(req.cadastralCode()))
                    .and(field(name("opr", "owner_id"), UUID.class).eq(req.ownerId()))
                    .and(field(name("opr", "active"), Boolean.class).isTrue())))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Owner is not an active owner of the cadastre");
            }
        }

        dsl.insertInto(table("reminder"))
            .columns(field(name("organization_id")), field(name("creator")), field(name("owner_id")),
                field(name("cadastral_code")), field(name("reminder_text")), field(name("due_time")))
            .values(orgId, creator, req.ownerId(), req.cadastralCode(), req.text(), req.dueTime())
            .execute();
    }

    /**
     * Reminders are personal work items. Deletion is creator-scoped for every role,
     * including managers/admins; administrative visibility does not grant ownership.
     * A missing/not-owned id is intentionally returned as 404 to avoid id probing.
     */
    @Transactional
    public void delete(Long id) {
        int deleted = dsl.deleteFrom(table("reminder")).where(field(name("id"), Long.class).eq(id)
            .and(field(name("organization_id"), UUID.class).eq(orgCtx.requiredOrganizationId()))
            .and(field(name("creator"), String.class).eq(orgCtx.subject())))
            .execute();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reminder not found");
        }
    }

    @Transactional
    public void update(Long id, String text, Instant dueTime) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        if (text == null || text.isBlank() || text.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeldetuletuse tekst peab olema 1–500 märki.");
        }
        if (dueTime == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeldetuletuse tähtaeg on kohustuslik.");
        }
        var reminder = dsl.select(field(name("owner_id"), UUID.class), field(name("deal_case_id"), UUID.class),
                field(name("due_time"), Instant.class), field(name("assignee_subject"), String.class))
            .from(table("reminder"))
            .where(field(name("id"), Long.class).eq(id)
                .and(field(name("organization_id"), UUID.class).eq(orgId))
                .and(field(name("creator"), String.class).eq(subject))
                .and(field(name("status"), String.class).eq("OPEN")))
            .fetchOne();
        if (reminder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aktiivset sinu loodud meeldetuletust ei leitud.");
        }
        int changed = dsl.update(table("reminder"))
            .set(field(name("reminder_text"), String.class), text.trim())
            .set(field(name("due_time"), Instant.class), dueTime)
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("id"), Long.class).eq(id)
                .and(field(name("organization_id"), UUID.class).eq(orgId))
                .and(field(name("status"), String.class).eq("OPEN")))
            .execute();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Meeldetuletus muutus samal ajal. Laadi vaade uuesti.");
        dsl.execute("""
            INSERT INTO reminder_event
                (organization_id, reminder_id, owner_id, deal_case_id, event_type,
                 old_due_time, new_due_time, from_assignee, to_assignee, reason, created_by_subject)
            VALUES (?, ?, ?, ?, 'UPDATED', ?, ?, ?, ?, ?, ?)
            """, orgId, id, reminder.value1(), reminder.value2(), reminder.value3(), dueTime,
            reminder.value4(), reminder.value4(), "Meeldetuletuse tekst või tähtaeg muudeti.", subject);
    }
}
