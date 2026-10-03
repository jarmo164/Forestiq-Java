package ee.forestiq.owner;

import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.OwnerListItem;

/** Read-optimized owner cursor queries for the interactive owner list. */
@Repository
public class OwnerCursorRepository {

    private static final Table<Record> OWNER = table(name("owner")).as("o");
    private static final Field<UUID> O_ID = field(name("o", "id"), UUID.class);
    private static final Field<UUID> O_ORG = field(name("o", "organization_id"), UUID.class);
    private static final Field<String> O_NAME = field(name("o", "display_name"), String.class);
    private static final Field<String> O_CODE = field(name("o", "personal_or_registry_code"), String.class);
    private static final Field<String> O_STATUS = field(name("o", "status"), String.class);
    private static final Field<String> O_PHONE = field(name("o", "phone"), String.class);
    private static final Field<String> O_ASSIGNED = field(name("o", "assigned_to_subject"), String.class);
    private static final Field<Instant> O_UPDATED = field(name("o", "updated_at"), Instant.class);
    private static final Field<Instant> O_STATUS_SET = field(name("o", "status_set_at"), Instant.class);

    private static final Table<Record> OWNER_PARCEL_RELATION = table(name("owner_parcel_relation")).as("opr");
    private static final Field<UUID> OPR_ORG = field(name("opr", "organization_id"), UUID.class);
    private static final Field<UUID> OPR_OWNER = field(name("opr", "owner_id"), UUID.class);
    private static final Field<UUID> OPR_PARCEL = field(name("opr", "parcel_id"), UUID.class);
    private static final Field<Boolean> OPR_ACTIVE = field(name("opr", "active"), Boolean.class);

    private final DSLContext dsl;

    public OwnerCursorRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public CursorSlice findOwners(UUID orgId, String search, String assignedSubject,
                                  Instant afterUpdatedAt, UUID afterId, int size) {
        Condition ownerCondition = ownerCondition(orgId, search, assignedSubject);
        if (afterUpdatedAt != null && afterId != null) {
            ownerCondition = ownerCondition.and(
                O_UPDATED.lt(afterUpdatedAt)
                    .or(O_UPDATED.eq(afterUpdatedAt).and(O_ID.gt(afterId)))
            );
        }

        var candidates = dsl.select(O_ID, O_NAME, O_CODE, O_STATUS, O_UPDATED, O_ASSIGNED, O_PHONE, O_STATUS_SET)
            .from(OWNER)
            .where(ownerCondition)
            .orderBy(O_UPDATED.desc(), O_ID.asc())
            .limit(size + 1)
            .fetch();

        boolean hasNext = candidates.size() > size;
        int resultSize = Math.min(size, candidates.size());
        if (resultSize == 0) {
            return new CursorSlice(List.of(), false, null, null);
        }

        var pageRows = candidates.subList(0, resultSize);
        List<UUID> ownerIds = new ArrayList<>(resultSize);
        for (var row : pageRows) ownerIds.add(row.get(O_ID));

        Field<Integer> countField = count(OPR_PARCEL).as("parcel_count");
        Map<UUID, Integer> parcelCounts = new HashMap<>();
        dsl.select(OPR_OWNER, countField)
            .from(OWNER_PARCEL_RELATION)
            .where(OPR_ORG.eq(orgId).and(OPR_ACTIVE.isTrue()).and(OPR_OWNER.in(ownerIds)))
            .groupBy(OPR_OWNER)
            .fetch()
            .forEach(row -> parcelCounts.put(row.get(OPR_OWNER), row.get(countField)));

        List<OwnerListItem> content = new ArrayList<>(resultSize);
        for (var row : pageRows) {
            UUID ownerId = row.get(O_ID);
            Integer parcelCount = parcelCounts.get(ownerId);
            content.add(new OwnerListItem(
                ownerId,
                row.get(O_NAME),
                row.get(O_CODE),
                row.get(O_STATUS),
                parcelCount == null ? 0L : parcelCount.longValue(),
                row.get(O_UPDATED),
                row.get(O_ASSIGNED),
                row.get(O_PHONE),
                row.get(O_STATUS_SET),
                List.of()
            ));
        }

        var last = pageRows.get(pageRows.size() - 1);
        return new CursorSlice(content, hasNext, last.get(O_UPDATED), last.get(O_ID));
    }

    private Condition ownerCondition(UUID orgId, String search, String assignedSubject) {
        Condition result = O_ORG.eq(orgId);
        if (assignedSubject != null) result = result.and(O_ASSIGNED.eq(assignedSubject));
        if (search == null || search.isBlank()) return result;
        String normalized = search.trim();
        Condition searchable = condition("{0} @@ websearch_to_tsquery('simple', {1})",
            field(name("o", "search_document")), val(normalized));
        String phoneDigits = normalized.replaceAll("\\D", "");
        if (phoneDigits.length() >= 3) {
            searchable = searchable.or(condition("regexp_replace({0}, '[^0-9]', '', 'g') LIKE {1}",
                O_PHONE, val("%" + phoneDigits + "%")));
        }
        return result.and(searchable);
    }

    public record CursorSlice(
        List<OwnerListItem> content,
        boolean hasNext,
        Instant nextUpdatedAt,
        UUID nextId
    ) { }
}
