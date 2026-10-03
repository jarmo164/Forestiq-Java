package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.DATA_CHANGE_SIGNAL;
import static ee.forestiq.jooq.Tables.OWNER;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.val;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerAssignmentRepository {

    private final DSLContext dsl;

    public OwnerAssignmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<UUID> findExistingOwnerIds(UUID orgId, List<UUID> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return List.of();
        return dsl.select(OWNER.ID)
            .from(OWNER)
            .where(OWNER.ORGANIZATION_ID.eq(orgId).and(OWNER.ID.in(ownerIds)))
            .fetchInto(UUID.class);
    }

    public void setCallerForOwners(UUID orgId, String assignee, List<UUID> ownerIds) {
        dsl.update(OWNER)
            .set(OWNER.ASSIGNED_TO_SUBJECT, assignee)
            .where(OWNER.ORGANIZATION_ID.eq(orgId).and(OWNER.ID.in(ownerIds)))
            .execute();
    }

    public void setStatusForOwners(UUID orgId, String status, List<UUID> ownerIds) {
        dsl.update(OWNER)
            .set(OWNER.STATUS, status)
            .set(OWNER.STATUS_SET_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(OWNER.ORGANIZATION_ID.eq(orgId).and(OWNER.ID.in(ownerIds)))
            .execute();
    }

    /** Marks the first time data-originated work was handed to a caller without removing its audit trail. */
    public void markDataSignalsAssigned(UUID orgId, List<UUID> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return;
        dsl.update(DATA_CHANGE_SIGNAL)
            .set(DATA_CHANGE_SIGNAL.FIRST_ASSIGNED_AT,
                coalesce(DATA_CHANGE_SIGNAL.FIRST_ASSIGNED_AT, val(OffsetDateTime.now(ZoneOffset.UTC))))
            .where(DATA_CHANGE_SIGNAL.ORGANIZATION_ID.eq(orgId)
                .and(DATA_CHANGE_SIGNAL.OWNER_ID.in(ownerIds))
                .and(DATA_CHANGE_SIGNAL.DISMISSED_AT.isNull()))
            .execute();
    }
}
