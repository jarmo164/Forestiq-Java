package ee.forestiq.inheritance;

import static ee.forestiq.jooq.Tables.DATA_CHANGE_SIGNAL;
import static ee.forestiq.jooq.Tables.INHERITANCE_CASE;
import static ee.forestiq.jooq.Tables.INHERITANCE_CASE_EVENT;
import static ee.forestiq.jooq.Tables.INHERITANCE_HEIR;
import static ee.forestiq.jooq.Tables.INHERITANCE_NOTICE_SYNC;
import static ee.forestiq.jooq.Tables.OWNER;
import static ee.forestiq.jooq.Tables.OWNER_ACTIVITY;
import static ee.forestiq.jooq.Tables.USER_STATUS_CHANGE_RECORD;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.selectOne;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.inheritance.InheritanceService.InheritanceCaseDetails;
import ee.forestiq.inheritance.InheritanceService.InheritanceEvent;
import ee.forestiq.inheritance.InheritanceService.InheritanceHeir;
import ee.forestiq.inheritance.InheritanceService.SaveInheritanceHeirRequest;
import ee.forestiq.inheritance.InheritanceService.StartInheritanceCaseRequest;
import ee.forestiq.owner.OwnerStatus;

@Repository
class InheritanceRepository {

    private final DSLContext dsl;

    InheritanceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    OwnerSnapshot ownerSnapshot(UUID orgId, UUID ownerId) {
        Record owner = dsl.select(OWNER.ID, OWNER.ORGANIZATION_ID, OWNER.PERSONAL_OR_REGISTRY_CODE, OWNER.STATUS)
            .from(OWNER)
            .where(OWNER.ID.eq(ownerId).and(OWNER.ORGANIZATION_ID.eq(orgId)))
            .fetchOne();
        if (owner == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Omanikku ei leitud.");
        return new OwnerSnapshot(owner.get(OWNER.ID), owner.get(OWNER.ORGANIZATION_ID),
            owner.get(OWNER.PERSONAL_OR_REGISTRY_CODE), owner.get(OWNER.STATUS));
    }

    List<OwnerSnapshot> findOwnersDueForNoticeCheck(OffsetDateTime staleBefore, int batchSize) {
        var o = OWNER.as("o");
        var s = INHERITANCE_NOTICE_SYNC.as("s");
        return dsl.select(o.ID, o.ORGANIZATION_ID, o.PERSONAL_OR_REGISTRY_CODE, o.STATUS)
            .from(o)
            .leftJoin(s).on(s.ORGANIZATION_ID.eq(o.ORGANIZATION_ID).and(s.OWNER_ID.eq(o.ID)))
            // PostgreSQL-specific regex is intentionally isolated in the persistence adapter.
            .where(o.STATUS.ne(OwnerStatus.DECEASED)
                .and(condition("{0} ~ '^[0-9]{11}$'", o.PERSONAL_OR_REGISTRY_CODE))
                .and(s.LAST_CHECKED_AT.isNull().or(s.LAST_CHECKED_AT.lt(staleBefore))))
            .orderBy(s.LAST_CHECKED_AT.asc().nullsFirst())
            .limit(batchSize)
            .fetch(record -> new OwnerSnapshot(record.value1(), record.value2(), record.value3(), record.value4()));
    }

    OwnerSnapshot findOwnerByPersonalCode(UUID orgId, String personalCode) {
        Record owner = dsl.select(OWNER.ID, OWNER.ORGANIZATION_ID, OWNER.PERSONAL_OR_REGISTRY_CODE, OWNER.STATUS)
            .from(OWNER)
            .where(OWNER.ORGANIZATION_ID.eq(orgId)
                .and(OWNER.PERSONAL_OR_REGISTRY_CODE.eq(personalCode)))
            .fetchOne();
        return owner == null ? null : new OwnerSnapshot(owner.get(OWNER.ID), owner.get(OWNER.ORGANIZATION_ID),
            owner.get(OWNER.PERSONAL_OR_REGISTRY_CODE), owner.get(OWNER.STATUS));
    }

    boolean noticeAlreadyImported(UUID orgId, String noticeNumber) {
        return dsl.fetchExists(selectOne().from(org.jooq.impl.DSL.table("inheritance_notice_import"))
            .where(org.jooq.impl.DSL.field("organization_id", UUID.class).eq(orgId)
                .and(org.jooq.impl.DSL.field("notice_number", String.class).eq(noticeNumber))));
    }

    void recordImportedNotice(UUID orgId, UUID ownerId, ParsedInheritanceNotice notice) {
        dsl.insertInto(org.jooq.impl.DSL.table("inheritance_notice_import"))
            .columns(org.jooq.impl.DSL.field("organization_id"), org.jooq.impl.DSL.field("notice_number"),
                org.jooq.impl.DSL.field("owner_id"), org.jooq.impl.DSL.field("announcement_date"),
                org.jooq.impl.DSL.field("source_url"), org.jooq.impl.DSL.field("death_date"),
                org.jooq.impl.DSL.field("certification_deadline"), org.jooq.impl.DSL.field("notary_name"),
                org.jooq.impl.DSL.field("notary_phone"))
            .values(orgId, notice.noticeNumber(), ownerId, notice.announcementDate(), blankToNull(notice.sourceUrl()),
                notice.deathDate(), notice.certificationDeadline(), blankToNull(notice.notary()),
                blankToNull(notice.phone()))
            .onConflictDoNothing().execute();
    }

    InheritanceCaseDetails latestCaseDetails(UUID orgId, UUID ownerId) {
        Record row = dsl.selectFrom(INHERITANCE_CASE)
            .where(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId).and(INHERITANCE_CASE.OWNER_ID.eq(ownerId)))
            .orderBy(INHERITANCE_CASE.STARTED_AT.desc())
            .limit(1)
            .fetchOne();
        return row == null ? null : details(row);
    }

    CaseSnapshot requireCase(UUID orgId, UUID caseId) {
        Record row = dsl.selectFrom(INHERITANCE_CASE)
            .where(INHERITANCE_CASE.ID.eq(caseId).and(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId)))
            .fetchOne();
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pärimisjuhtumit ei leitud.");
        return new CaseSnapshot(caseId, row.get(INHERITANCE_CASE.OWNER_ID), row.get(INHERITANCE_CASE.STATUS));
    }

    InheritanceService.InheritanceCaseWorkspace caseWorkspace(UUID orgId, UUID caseId) {
        var row = dsl.fetchOne("""
            SELECT c.id, c.owner_id, c.assigned_to_subject, o.display_name, o.personal_or_registry_code
              FROM inheritance_case c
              JOIN owner o ON o.id=c.owner_id AND o.organization_id=c.organization_id
             WHERE c.organization_id=? AND c.id=?
            """, orgId, caseId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pärimisjuhtumit ei leitud.");
        return new InheritanceService.InheritanceCaseWorkspace(
            row.get("id", UUID.class), row.get("owner_id", UUID.class),
            row.get("display_name", String.class), maskCode(row.get("personal_or_registry_code", String.class)),
            row.get("assigned_to_subject", String.class), details(orgId, caseId));
    }

    InheritanceService.InheritanceCasePage searchCases(UUID orgId, String query, String status,
                                                       String assignedTo, boolean overdue,
                                                       String visibleSubject, int page, int size) {
        var c = org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("inheritance_case")).as("c");
        var o = org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("owner")).as("o");
        var cId = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "id"), UUID.class);
        var organizationField = org.jooq.impl.DSL.field(
            org.jooq.impl.DSL.name("c", "organization_id"), UUID.class);
        var ownerId = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "owner_id"), UUID.class);
        var ownerName = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "display_name"), String.class);
        var ownerCode = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "personal_or_registry_code"), String.class);
        var notice = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "source_notice_number"), String.class);
        var caseStatus = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "status"), String.class);
        var announcement = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "announcement_date"), java.time.LocalDate.class);
        var deadline = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "certification_deadline"), java.time.LocalDate.class);
        var assigned = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "assigned_to_subject"), String.class);
        var updated = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("c", "updated_at"), OffsetDateTime.class);
        Condition where = organizationField.eq(orgId);
        if (query != null && !query.isBlank()) {
            String value = "%" + query.trim().toLowerCase() + "%";
            where = where.and(org.jooq.impl.DSL.lower(ownerName).like(value)
                .or(org.jooq.impl.DSL.lower(notice).like(value))
                .or(ownerCode.eq(query.trim())));
        }
        if (status != null && !status.isBlank()) where = where.and(caseStatus.eq(status));
        if ("UNASSIGNED".equals(assignedTo)) where = where.and(assigned.isNull());
        else if (assignedTo != null && !assignedTo.isBlank()) where = where.and(assigned.eq(assignedTo));
        if (visibleSubject != null) where = where.and(assigned.eq(visibleSubject));
        if (overdue) where = where.and(deadline.lt(LocalDate.now()).and(caseStatus.notIn("SETTLED", "CLOSED", "NOT_RELEVANT")));

        int total = dsl.selectCount().from(c.join(o).on(
                org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "id"), UUID.class).eq(ownerId)
                    .and(org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "organization_id"), UUID.class)
                        .eq(organizationField))))
            .where(where).fetchOne(0, int.class);
        var heirCount = org.jooq.impl.DSL.field(
            "(SELECT count(*) FROM inheritance_heir h WHERE h.inheritance_case_id=c.id)", Integer.class);
        var contactedCount = org.jooq.impl.DSL.field(
            "(SELECT count(*) FROM inheritance_heir h WHERE h.inheritance_case_id=c.id AND h.contact_status <> 'UNVERIFIED')",
            Integer.class);
        List<InheritanceService.InheritanceCaseListItem> items = dsl.select(cId, ownerId, ownerName, ownerCode,
                notice, announcement, deadline, caseStatus, assigned, updated, heirCount, contactedCount)
            .from(c.join(o).on(
                org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "id"), UUID.class).eq(ownerId)
                    .and(org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("o", "organization_id"), UUID.class)
                        .eq(organizationField))))
            .where(where).orderBy(deadline.asc().nullsLast(), updated.desc())
            .limit(size).offset(page * size)
            .fetch(record -> new InheritanceService.InheritanceCaseListItem(
                record.get(cId), record.get(ownerId), record.get(ownerName), maskCode(record.get(ownerCode)),
                record.get(notice), record.get(announcement), record.get(deadline), record.get(caseStatus),
                record.get(assigned), nvl(record.get(heirCount), 0), nvl(record.get(contactedCount), 0),
                toInstant(record.get(updated))));
        return new InheritanceService.InheritanceCasePage(items, page, size, total,
            size == 0 ? 0 : (int) Math.ceil((double) total / size));
    }

    void assignCase(UUID orgId, UUID caseId, String assignedTo) {
        int changed = dsl.update(org.jooq.impl.DSL.table("inheritance_case"))
            .set(org.jooq.impl.DSL.field("assigned_to_subject"), blankToNull(assignedTo))
            .set(org.jooq.impl.DSL.field("updated_at"), OffsetDateTime.now(ZoneOffset.UTC))
            .where(org.jooq.impl.DSL.field("organization_id", UUID.class).eq(orgId)
                .and(org.jooq.impl.DSL.field("id", UUID.class).eq(caseId))).execute();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pärimisjuhtumit ei leitud.");
    }

    InheritanceCaseDetails details(UUID orgId, UUID caseId) {
        Record row = dsl.selectFrom(INHERITANCE_CASE)
            .where(INHERITANCE_CASE.ID.eq(caseId).and(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId)))
            .fetchOne();
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pärimisjuhtumit ei leitud.");
        return details(row);
    }

    InheritanceCaseDetails findByNotice(UUID orgId, UUID ownerId, String noticeNumber) {
        Record row = dsl.selectFrom(INHERITANCE_CASE)
            .where(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId)
                .and(INHERITANCE_CASE.OWNER_ID.eq(ownerId))
                .and(INHERITANCE_CASE.SOURCE_NOTICE_NUMBER.eq(noticeNumber)))
            .fetchOne();
        return row == null ? null : details(row);
    }

    boolean hasOpenCase(UUID orgId, UUID ownerId) {
        return dsl.fetchExists(selectOne().from(INHERITANCE_CASE)
            .where(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId)
                .and(INHERITANCE_CASE.OWNER_ID.eq(ownerId))
                .and(INHERITANCE_CASE.STATUS.notIn("SETTLED", "CLOSED", "NOT_RELEVANT"))));
    }

    UUID insertCase(UUID orgId, UUID ownerId, StartInheritanceCaseRequest request, String subject) {
        UUID caseId = UUID.randomUUID();
        try {
            dsl.insertInto(INHERITANCE_CASE)
                .columns(INHERITANCE_CASE.ID, INHERITANCE_CASE.ORGANIZATION_ID, INHERITANCE_CASE.OWNER_ID,
                    INHERITANCE_CASE.SOURCE_NOTICE_NUMBER, INHERITANCE_CASE.SOURCE_URL,
                    INHERITANCE_CASE.ANNOUNCEMENT_DATE, INHERITANCE_CASE.DEATH_DATE,
                    INHERITANCE_CASE.CERTIFICATION_DEADLINE, INHERITANCE_CASE.NOTARY_NAME,
                    INHERITANCE_CASE.NOTARY_PHONE, INHERITANCE_CASE.STATUS, INHERITANCE_CASE.STARTED_AT,
                    INHERITANCE_CASE.CREATED_BY_SUBJECT)
                .values(caseId, orgId, ownerId, blankToNull(request.sourceNoticeNumber()), blankToNull(request.sourceUrl()),
                    request.announcementDate(), request.deathDate(), request.certificationDeadline(),
                    blankToNull(request.notaryName()), blankToNull(request.notaryPhone()), "NOTICE_RECEIVED",
                    dbTime(Instant.now()), subject)
                .execute();
            return caseId;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Pärimisjuhtumi ametlik teade on juba organisatsioonis seotud.", exception);
        }
    }

    UUID insertHeir(UUID orgId, UUID caseId, SaveInheritanceHeirRequest request) {
        UUID heirId = UUID.randomUUID();
        dsl.insertInto(INHERITANCE_HEIR)
            .columns(INHERITANCE_HEIR.ID, INHERITANCE_HEIR.INHERITANCE_CASE_ID, INHERITANCE_HEIR.ORGANIZATION_ID,
                INHERITANCE_HEIR.DISPLAY_NAME, INHERITANCE_HEIR.PERSONAL_CODE, INHERITANCE_HEIR.REGISTRY_CODE,
                INHERITANCE_HEIR.INHERITANCE_SHARE, INHERITANCE_HEIR.RELATION_TO_DECEASED, INHERITANCE_HEIR.PHONE,
                INHERITANCE_HEIR.EMAIL, INHERITANCE_HEIR.CONTACT_STATUS, INHERITANCE_HEIR.ASSIGNED_TO_SUBJECT,
                INHERITANCE_HEIR.SOURCE)
            .values(heirId, caseId, orgId, request.displayName().trim(), blankToNull(request.personalCode()),
                blankToNull(request.registryCode()), blankToNull(request.inheritanceShare()),
                blankToNull(request.relationToDeceased()), blankToNull(request.phone()), blankToNull(request.email()),
                request.contactStatus(), blankToNull(request.assignedToSubject()),
                blankToNull(request.source()) == null ? "MANUAL" : request.source().trim())
            .execute();
        return heirId;
    }

    int updateHeir(UUID orgId, UUID caseId, UUID heirId, SaveInheritanceHeirRequest request) {
        return dsl.update(INHERITANCE_HEIR)
            .set(INHERITANCE_HEIR.DISPLAY_NAME, request.displayName().trim())
            .set(INHERITANCE_HEIR.PERSONAL_CODE, blankToNull(request.personalCode()))
            .set(INHERITANCE_HEIR.REGISTRY_CODE, blankToNull(request.registryCode()))
            .set(INHERITANCE_HEIR.INHERITANCE_SHARE, blankToNull(request.inheritanceShare()))
            .set(INHERITANCE_HEIR.RELATION_TO_DECEASED, blankToNull(request.relationToDeceased()))
            .set(INHERITANCE_HEIR.PHONE, blankToNull(request.phone()))
            .set(INHERITANCE_HEIR.EMAIL, blankToNull(request.email()))
            .set(INHERITANCE_HEIR.CONTACT_STATUS, request.contactStatus())
            .set(INHERITANCE_HEIR.ASSIGNED_TO_SUBJECT, blankToNull(request.assignedToSubject()))
            .set(INHERITANCE_HEIR.UPDATED_AT, dbTime(Instant.now()))
            .where(INHERITANCE_HEIR.ID.eq(heirId)
                .and(INHERITANCE_HEIR.INHERITANCE_CASE_ID.eq(caseId))
                .and(INHERITANCE_HEIR.ORGANIZATION_ID.eq(orgId)))
            .execute();
    }

    void updateCaseStatus(UUID orgId, UUID caseId, String status, boolean terminal) {
        OffsetDateTime now = dbTime(Instant.now());
        dsl.update(INHERITANCE_CASE)
            .set(INHERITANCE_CASE.STATUS, status)
            .set(INHERITANCE_CASE.ENDED_AT, terminal ? now : null)
            .set(INHERITANCE_CASE.UPDATED_AT, now)
            .where(INHERITANCE_CASE.ID.eq(caseId).and(INHERITANCE_CASE.ORGANIZATION_ID.eq(orgId)))
            .execute();
    }

    void markOwnerDeceased(UUID orgId, OwnerSnapshot owner, String noticeNumber, String subject) {
        OffsetDateTime now = dbTime(Instant.now());
        dsl.update(OWNER)
            .set(OWNER.STATUS, OwnerStatus.DECEASED)
            .set(OWNER.STATUS_SET_AT, now)
            .set(OWNER.UPDATED_AT, now)
            .where(OWNER.ORGANIZATION_ID.eq(orgId).and(OWNER.ID.eq(owner.id())))
            .execute();
        dsl.insertInto(DATA_CHANGE_SIGNAL)
            .columns(DATA_CHANGE_SIGNAL.ID, DATA_CHANGE_SIGNAL.ORGANIZATION_ID, DATA_CHANGE_SIGNAL.OWNER_ID,
                DATA_CHANGE_SIGNAL.SIGNAL_TYPE, DATA_CHANGE_SIGNAL.SOURCE_REGISTRY, DATA_CHANGE_SIGNAL.SOURCE_KEY,
                DATA_CHANGE_SIGNAL.SUMMARY, DATA_CHANGE_SIGNAL.PAYLOAD, DATA_CHANGE_SIGNAL.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), orgId, owner.id(), "DECEASED_OWNER", "INHERITANCE_NOTICES", noticeNumber,
                "Ametlik pärimisteade tuvastas surnud omaniku; pärimisjuhtum vajab käsitlemist.",
                JSONB.valueOf("{\"noticeNumber\":\"" + noticeNumber.replace("\"", "") + "\"}"), subject)
            .onConflictDoNothing()
            .execute();
        dsl.insertInto(USER_STATUS_CHANGE_RECORD)
            .columns(USER_STATUS_CHANGE_RECORD.ORGANIZATION_ID, USER_STATUS_CHANGE_RECORD.USER_SUBJECT,
                USER_STATUS_CHANGE_RECORD.FROM_STATUS, USER_STATUS_CHANGE_RECORD.TO_STATUS)
            .values(orgId, subject, owner.status(), OwnerStatus.DECEASED)
            .execute();
    }

    void recordNoticeCheck(UUID orgId, UUID ownerId, String noticeNumber, String error) {
        OffsetDateTime now = dbTime(Instant.now());
        dsl.insertInto(INHERITANCE_NOTICE_SYNC)
            .columns(INHERITANCE_NOTICE_SYNC.ORGANIZATION_ID, INHERITANCE_NOTICE_SYNC.OWNER_ID,
                INHERITANCE_NOTICE_SYNC.LAST_CHECKED_AT, INHERITANCE_NOTICE_SYNC.LAST_NOTICE_NUMBER,
                INHERITANCE_NOTICE_SYNC.LAST_ERROR)
            .values(orgId, ownerId, now, noticeNumber, error)
            .onConflict(INHERITANCE_NOTICE_SYNC.ORGANIZATION_ID, INHERITANCE_NOTICE_SYNC.OWNER_ID)
            .doUpdate()
            .set(INHERITANCE_NOTICE_SYNC.LAST_CHECKED_AT, now)
            .set(INHERITANCE_NOTICE_SYNC.LAST_NOTICE_NUMBER, noticeNumber)
            .set(INHERITANCE_NOTICE_SYNC.LAST_ERROR, error)
            .execute();
    }

    void addEvent(UUID orgId, UUID caseId, String eventType, String description, Map<String, String> data, String subject) {
        dsl.insertInto(INHERITANCE_CASE_EVENT)
            .columns(INHERITANCE_CASE_EVENT.ID, INHERITANCE_CASE_EVENT.INHERITANCE_CASE_ID,
                INHERITANCE_CASE_EVENT.ORGANIZATION_ID, INHERITANCE_CASE_EVENT.EVENT_TYPE,
                INHERITANCE_CASE_EVENT.DESCRIPTION, INHERITANCE_CASE_EVENT.EVENT_DATA,
                INHERITANCE_CASE_EVENT.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), caseId, orgId, eventType, description, JSONB.valueOf(toJson(data)), subject)
            .execute();
    }

    void addOwnerActivity(UUID orgId, UUID ownerId, String activityType, String content, String subject) {
        dsl.insertInto(OWNER_ACTIVITY)
            .columns(OWNER_ACTIVITY.ORGANIZATION_ID, OWNER_ACTIVITY.OWNER_ID, OWNER_ACTIVITY.ACTIVITY_TYPE,
                OWNER_ACTIVITY.CONTENT, OWNER_ACTIVITY.CREATED_BY_SUBJECT)
            .values(orgId, ownerId, activityType, content, subject)
            .execute();
    }

    private InheritanceCaseDetails details(Record row) {
        UUID caseId = row.get(INHERITANCE_CASE.ID);
        List<InheritanceHeir> heirs = dsl.selectFrom(INHERITANCE_HEIR)
            .where(INHERITANCE_HEIR.INHERITANCE_CASE_ID.eq(caseId))
            .orderBy(INHERITANCE_HEIR.CREATED_AT.asc())
            .fetch(record -> new InheritanceHeir(
                record.get(INHERITANCE_HEIR.ID), record.get(INHERITANCE_HEIR.DISPLAY_NAME),
                record.get(INHERITANCE_HEIR.PERSONAL_CODE), record.get(INHERITANCE_HEIR.REGISTRY_CODE),
                record.get(INHERITANCE_HEIR.INHERITANCE_SHARE), record.get(INHERITANCE_HEIR.RELATION_TO_DECEASED),
                record.get(INHERITANCE_HEIR.PHONE), record.get(INHERITANCE_HEIR.EMAIL),
                record.get(INHERITANCE_HEIR.CONTACT_STATUS), record.get(INHERITANCE_HEIR.ASSIGNED_TO_SUBJECT),
                record.get(INHERITANCE_HEIR.SOURCE)));
        List<InheritanceEvent> events = dsl.selectFrom(INHERITANCE_CASE_EVENT)
            .where(INHERITANCE_CASE_EVENT.INHERITANCE_CASE_ID.eq(caseId))
            .orderBy(INHERITANCE_CASE_EVENT.CREATED_AT.desc())
            .fetch(record -> new InheritanceEvent(
                record.get(INHERITANCE_CASE_EVENT.ID), record.get(INHERITANCE_CASE_EVENT.EVENT_TYPE),
                record.get(INHERITANCE_CASE_EVENT.DESCRIPTION), toInstant(record.get(INHERITANCE_CASE_EVENT.CREATED_AT)),
                record.get(INHERITANCE_CASE_EVENT.CREATED_BY_SUBJECT)));
        return new InheritanceCaseDetails(caseId, row.get(INHERITANCE_CASE.OWNER_ID),
            row.get(INHERITANCE_CASE.SOURCE_NOTICE_NUMBER), row.get(INHERITANCE_CASE.SOURCE_URL),
            row.get(INHERITANCE_CASE.ANNOUNCEMENT_DATE), row.get(INHERITANCE_CASE.DEATH_DATE),
            row.get(INHERITANCE_CASE.CERTIFICATION_DEADLINE), row.get(INHERITANCE_CASE.NOTARY_NAME),
            row.get(INHERITANCE_CASE.NOTARY_PHONE), row.get(INHERITANCE_CASE.STATUS),
            toInstant(row.get(INHERITANCE_CASE.STARTED_AT)), toInstant(row.get(INHERITANCE_CASE.ENDED_AT)), heirs, events);
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String maskCode(String value) {
        if (value == null || value.length() < 4) return "—";
        return "*".repeat(Math.max(0, value.length() - 4)) + value.substring(value.length() - 4);
    }

    private static <T> T nvl(T value, T fallback) { return value == null ? fallback : value; }

    private static String toJson(Map<String, String> data) {
        return "{" + data.entrySet().stream()
            .map(entry -> "\"" + entry.getKey().replace("\"", "") + "\":\"" + entry.getValue().replace("\"", "") + "\"")
            .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    record OwnerSnapshot(UUID id, UUID organizationId, String personalCode, String status) { }
    record CaseSnapshot(UUID id, UUID ownerId, String status) { }
}
