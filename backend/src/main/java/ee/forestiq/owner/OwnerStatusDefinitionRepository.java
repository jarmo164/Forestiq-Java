package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.OWNER_STATUS_DEFINITION;
import static org.jooq.impl.DSL.selectOne;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.OwnerStatusDefinition;
import ee.forestiq.owner.OwnerDtos.SaveOwnerStatusRequest;

@Repository
public class OwnerStatusDefinitionRepository {

    private final DSLContext dsl;
    private final OwnerStatusDefinitionCache cache;

    public OwnerStatusDefinitionRepository(DSLContext dsl, OwnerStatusDefinitionCache cache) {
        this.dsl = dsl;
        this.cache = cache;
    }

    public List<OwnerStatusDefinition> findStatusDefinitions(UUID orgId) {
        return cache.get(orgId, () -> dsl.select(OWNER_STATUS_DEFINITION.ID, OWNER_STATUS_DEFINITION.COLOR_HEX,
                OWNER_STATUS_DEFINITION.DAYS_OUT_OF_SEARCH, OWNER_STATUS_DEFINITION.IS_PROTECTED)
            .from(OWNER_STATUS_DEFINITION)
            .where(OWNER_STATUS_DEFINITION.ORGANIZATION_ID.eq(orgId))
            .orderBy(OWNER_STATUS_DEFINITION.IS_PROTECTED.desc(), OWNER_STATUS_DEFINITION.ID)
            .fetch(record -> new OwnerStatusDefinition(record.value1(), record.value2(), record.value3(), record.value4())));
    }

    /**
     * Validation is intentionally uncached: a write-path status decision must
     * always observe PostgreSQL truth rather than wait for a reference-data TTL.
     */
    public boolean statusDefinitionExists(UUID orgId, String statusId) {
        return dsl.fetchExists(selectOne()
            .from(OWNER_STATUS_DEFINITION)
            .where(OWNER_STATUS_DEFINITION.ORGANIZATION_ID.eq(orgId)
                .and(OWNER_STATUS_DEFINITION.ID.eq(statusId))));
    }

    public void upsertStatusDefinition(UUID orgId, SaveOwnerStatusRequest request) {
        dsl.insertInto(OWNER_STATUS_DEFINITION)
            .columns(OWNER_STATUS_DEFINITION.ID, OWNER_STATUS_DEFINITION.ORGANIZATION_ID,
                OWNER_STATUS_DEFINITION.COLOR_HEX, OWNER_STATUS_DEFINITION.DAYS_OUT_OF_SEARCH)
            .values(request.id(), orgId, request.colorHex(), request.durationDays())
            .onConflict(OWNER_STATUS_DEFINITION.ORGANIZATION_ID, OWNER_STATUS_DEFINITION.ID)
            .doUpdate()
            .set(OWNER_STATUS_DEFINITION.COLOR_HEX, request.colorHex())
            .set(OWNER_STATUS_DEFINITION.DAYS_OUT_OF_SEARCH, request.durationDays())
            .execute();
        cache.invalidate(orgId);
    }

    public void deleteStatusDefinition(UUID orgId, String id) {
        dsl.deleteFrom(OWNER_STATUS_DEFINITION)
            .where(OWNER_STATUS_DEFINITION.ORGANIZATION_ID.eq(orgId)
                .and(OWNER_STATUS_DEFINITION.ID.eq(id))
                .and(OWNER_STATUS_DEFINITION.IS_PROTECTED.eq(false)))
            .execute();
        cache.invalidate(orgId);
    }
}
