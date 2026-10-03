package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.OWNER;
import static ee.forestiq.jooq.Tables.OWNER_PARCEL_RELATION;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.val;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ee.forestiq.jooq.tables.LandParcel;
import ee.forestiq.jooq.tables.Owner;
import ee.forestiq.jooq.tables.OwnerParcelRelation;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.OwnerListItem;
import ee.forestiq.owner.OwnerDtos.PagedResponse;

@Repository
public class MyWorkQueryRepository {

    private final DSLContext dsl;

    public MyWorkQueryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public PagedResponse<OwnerListItem> searchAssignedOwners(UUID orgId, String subject, String search, Pagination.PageRequest pagination) {
        Owner o = OWNER.as("o");
        OwnerParcelRelation opr = OWNER_PARCEL_RELATION.as("opr");
        LandParcel p = LAND_PARCEL.as("p");
        Field<Integer> parcelCount = count(p.ID).as("parcel_count");

        Condition filter = assignedOwnerCondition(o, orgId, subject, search);
        long total = dsl.selectCount().from(o).where(filter).fetchOne(0, long.class);

        List<OwnerListItem> items = dsl.select(
                o.ID, o.DISPLAY_NAME, o.PERSONAL_OR_REGISTRY_CODE, o.STATUS, parcelCount,
                o.UPDATED_AT, o.ASSIGNED_TO_SUBJECT, o.PHONE, o.STATUS_SET_AT)
            .from(o)
            .leftJoin(opr)
            .on(opr.OWNER_ID.eq(o.ID)
                .and(opr.ORGANIZATION_ID.eq(o.ORGANIZATION_ID))
                .and(opr.ACTIVE.isTrue()))
            .leftJoin(p)
            .on(p.ID.eq(opr.PARCEL_ID)
                .and(p.ORGANIZATION_ID.eq(o.ORGANIZATION_ID)))
            .where(filter)
            .groupBy(o.ID, o.DISPLAY_NAME, o.PERSONAL_OR_REGISTRY_CODE, o.STATUS, o.UPDATED_AT, o.ASSIGNED_TO_SUBJECT, o.PHONE, o.STATUS_SET_AT)
            .orderBy(o.STATUS_SET_AT.asc().nullsLast(), o.UPDATED_AT.desc())
            .limit(pagination.size()).offset(pagination.offset())
            .fetch(r -> new OwnerListItem(
                r.get(o.ID), r.get(o.DISPLAY_NAME), r.get(o.PERSONAL_OR_REGISTRY_CODE), r.get(o.STATUS),
                r.get(parcelCount).longValue(), instant(r.get(o.UPDATED_AT)), r.get(o.ASSIGNED_TO_SUBJECT),
                r.get(o.PHONE), instant(r.get(o.STATUS_SET_AT)), List.of()));

        return new PagedResponse<>(items, pagination.page(), pagination.size(), total, Pagination.totalPages(total, pagination.size()));
    }

    public UUID findNextAssignedOwnerId(UUID orgId, String subject) {
        return dsl.select(OWNER.ID)
            .from(OWNER)
            .where(OWNER.ORGANIZATION_ID.eq(orgId)
                .and(OWNER.ASSIGNED_TO_SUBJECT.eq(subject))
                .and(OWNER.STATUS.in("ASSIGNED", "CONTACTED")))
            .orderBy(OWNER.STATUS_SET_AT.asc().nullsLast())
            .limit(1)
            .fetchOne(0, UUID.class);
    }

    private Condition assignedOwnerCondition(Owner owner, UUID orgId, String subject, String search) {
        Condition filter = owner.ORGANIZATION_ID.eq(orgId)
            .and(owner.ASSIGNED_TO_SUBJECT.eq(subject));

        if (search != null && !search.isBlank()) {
            filter = filter.and(condition("{0} @@ websearch_to_tsquery('simple', {1})",
                owner.SEARCH_DOCUMENT, val(search.trim())));
        }

        return filter;
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
