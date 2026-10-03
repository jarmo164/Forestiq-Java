package ee.forestiq.registry;

import static ee.forestiq.jooq.tables.LandParcel.LAND_PARCEL;
import static ee.forestiq.jooq.tables.RegistrySyncLog.REGISTRY_SYNC_LOG;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.registry.ExternalRegistryService.ForestRecord;
import ee.forestiq.registry.ExternalRegistryService.MaaametCadastre;

/** Persistence adapter for official registry snapshots. Provider orchestration stays in RegistrySyncService. */
@Repository
class RegistryDataRepository {

    private final DSLContext dsl;
    private final RegistryPayloadPolicy payloadPolicy;

    RegistryDataRepository(DSLContext dsl, RegistryPayloadPolicy payloadPolicy) {
        this.dsl = dsl;
        this.payloadPolicy = payloadPolicy;
    }

    void ensureParcelExists(UUID orgId, String cadastralCode) {
        boolean exists = dsl.fetchExists(selectOne().from(table("land_parcel"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Kinnistut ei leitud organisatsiooni andmetest");
    }

    ParcelOwnerRef parcelOwnerRef(UUID orgId, String cadastralCode) {
        UUID parcelId = dsl.select(field(name("id"), UUID.class))
            .from(table("land_parcel"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode)))
            .fetchOne(field(name("id"), UUID.class));
        if (parcelId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Kinnistut ei leitud organisatsiooni andmetest");

        List<UUID> ownerIds = dsl.selectDistinct(field(name("owner_id"), UUID.class))
            .from(table("owner_parcel_relation"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("parcel_id"), UUID.class).eq(parcelId))
                .and(field(name("active"), Boolean.class).isTrue()))
            .orderBy(field(name("owner_id")).asc())
            .fetch(field(name("owner_id"), UUID.class));
        return new ParcelOwnerRef(parcelId, ownerIds);
    }

    int updateMaaametAttributes(UUID orgId, String cadastralCode, MaaametCadastre data) {
        return dsl.execute("""
            UPDATE land_parcel
               SET name = ?, address = ?, county = ?, municipality = ?, reg_nr = ?, parcel_type = ?,
                   area_hectares = ?, forest_area = ?, arable_area = ?, yard_area = ?, other_area = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE organization_id = ? AND cadastral_code = ?
            """, data.address(), data.address(), data.county(), data.municipality(), data.registryPartNumber(),
            data.ownershipForm(), nvl(data.areaHectares(), BigDecimal.ZERO), data.forestHectares(), data.arableHectares(),
            data.yardHectares(), data.otherHectares(), orgId, cadastralCode);
    }

    void clearBoundary(UUID orgId, String cadastralCode) {
        dsl.update(table("land_parcel"))
            .set(field(name("boundary")), (Object) null)
            .set(field(name("updated_at"), Instant.class), Instant.now())
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode)))
            .execute();
    }

    void updateBoundary(UUID orgId, String cadastralCode, String boundaryGeoJson) {
        dsl.execute("""
            UPDATE land_parcel
               SET boundary = ST_SetSRID(ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(?::json))), 4326),
                   updated_at = CURRENT_TIMESTAMP
             WHERE organization_id = ? AND cadastral_code = ?
            """, boundaryGeoJson, orgId, cadastralCode);
    }

    Set<String> activeNotificationKeys(UUID orgId, String cadastralCode) {
        return new LinkedHashSet<>(dsl.select(field(name("notification_number"), Object.class))
            .from(table("cadastre_notification"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                .and(field(name("archived"), Boolean.class).eq(false)))
            .fetchInto(Object.class).stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList());
    }

    int replaceForestNotifications(UUID orgId, String cadastralCode, List<ForestRecord> records) {
        dsl.deleteFrom(table("cadastre_notification"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                .and(field(name("archived"), Boolean.class).eq(false)))
            .execute();
        int count = 0;
        for (ForestRecord record : records) {
            dsl.insertInto(table("cadastre_notification"))
                .columns(field(name("organization_id"), UUID.class), field(name("cadastral_code"), String.class),
                    field(name("notification_number"), Long.class), field(name("work_code"), String.class),
                    field(name("decision"), String.class), field(name("area"), BigDecimal.class))
                .values(orgId, cadastralCode, record.sourceId(), record.workCode(), record.decision(), record.area())
                .execute();
            count++;
        }
        return count;
    }

    Map<String, String> forestPlanFeatureVersions(UUID orgId, String cadastralCode) {
        Map<String, String> versions = new LinkedHashMap<>();
        dsl.select(field(name("source_id"), String.class), field(name("attributes"), String.class))
            .from(table("forest_registry_feature"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                .and(field(name("source_layer"), String.class).eq("METSAREGISTER_ERALDISED")))
            .fetch()
            .forEach(record -> versions.put(record.value1(), payloadPolicy.storedFeatureHash(record.value2())));
        return versions;
    }

    int replaceForestPlan(UUID orgId, String cadastralCode, List<ForestRecord> records) {
        dsl.deleteFrom(table("forest_registry_feature"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                .and(field(name("source_layer"), String.class).eq("METSAREGISTER_ERALDISED")))
            .execute();
        int count = 0;
        for (ForestRecord record : records) {
            String attributes = payloadPolicy.compactFeatureAttributes(record.raw());
            dsl.insertInto(table("forest_registry_feature"))
                .columns(field(name("organization_id")), field(name("cadastral_code")), field(name("source_layer")),
                    field(name("source_id")), field(name("title")), field(name("work_code")), field(name("decision")),
                    field(name("area")), field(name("volume")), field(name("attributes")))
                .values(orgId, cadastralCode, "METSAREGISTER_ERALDISED",
                    record.sourceId() == null ? null : record.sourceId().toString(), record.title(), record.workCode(),
                    record.decision(), record.area(), record.volume(), attributes)
                .execute();
            count++;
        }
        if (count > 0) {
            dsl.update(table("land_parcel"))
                .set(field(name("mk_date")), System.currentTimeMillis())
                .where(field(name("organization_id"), UUID.class).eq(orgId)
                    .and(field(name("cadastral_code"), String.class).eq(cadastralCode)))
                .execute();
        }
        return count;
    }

    List<ParcelRef> staleParcels(UUID organizationId, Instant cadastreCutoff, Instant forestCutoff, int batchSize) {
        String tenantClause = organizationId == null ? "" : "AND p.organization_id = ?";
        List<Object> params = new ArrayList<>(List.of(cadastreCutoff, forestCutoff, forestCutoff));
        if (organizationId != null) params.add(organizationId);
        params.add(Math.max(1, Math.min(batchSize, 250)));
        return dsl.fetch("""
            SELECT p.organization_id, p.cadastral_code
            FROM land_parcel p
            WHERE (NOT EXISTS (SELECT 1 FROM registry_sync_log l WHERE l.organization_id = p.organization_id
                AND l.cadastral_code = p.cadastral_code AND l.registry_name = 'MAAAMET_WFS' AND l.status = 'SUCCESS' AND l.created_at >= ?::timestamptz)
               OR NOT EXISTS (SELECT 1 FROM registry_sync_log l WHERE l.organization_id = p.organization_id
                AND l.cadastral_code = p.cadastral_code AND l.registry_name = 'METSAREGISTER_TEATISED' AND l.status = 'SUCCESS' AND l.created_at >= ?::timestamptz)
               OR NOT EXISTS (SELECT 1 FROM registry_sync_log l WHERE l.organization_id = p.organization_id
                AND l.cadastral_code = p.cadastral_code AND l.registry_name = 'METSAREGISTER_ERALDISED' AND l.status = 'SUCCESS' AND l.created_at >= ?::timestamptz))
            """ + tenantClause + """
            ORDER BY EXISTS (SELECT 1 FROM data_change_signal s WHERE s.parcel_id = p.id AND s.dismissed_at IS NULL) DESC,
                     p.updated_at ASC NULLS FIRST
            LIMIT ?
            """, params.toArray())
            .map(record -> new ParcelRef(record.get("organization_id", UUID.class),
                record.get("cadastral_code", String.class)));
    }

    List<ParcelRef> staleParcelsForSource(UUID organizationId, String registry, Instant cutoff, int batchSize) {
        var parcel = LAND_PARCEL.as("p");
        var syncLog = REGISTRY_SYNC_LOG.as("l");
        var auditLog = REGISTRY_SYNC_LOG.as("a");
        int limit = Math.max(1, Math.min(batchSize, 250));
        OffsetDateTime cutoffAt = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);

        var lastSyncAt = select(max(auditLog.CREATED_AT))
            .from(auditLog)
            .where(auditLog.ORGANIZATION_ID.eq(parcel.ORGANIZATION_ID))
            .and(auditLog.CADASTRAL_CODE.eq(parcel.CADASTRAL_CODE))
            .and(auditLog.REGISTRY_NAME.eq(registry))
            .asField();

        return dsl.select(parcel.ORGANIZATION_ID, parcel.CADASTRAL_CODE)
            .from(parcel)
            .where(parcel.ORGANIZATION_ID.eq(organizationId))
            .and(notExists(
                selectOne().from(syncLog)
                    .where(syncLog.ORGANIZATION_ID.eq(parcel.ORGANIZATION_ID))
                    .and(syncLog.CADASTRAL_CODE.eq(parcel.CADASTRAL_CODE))
                    .and(syncLog.REGISTRY_NAME.eq(registry))
                    .and(syncLog.STATUS.eq("SUCCESS"))
                    .and(syncLog.CREATED_AT.ge(cutoffAt))
            ))
            .orderBy(lastSyncAt.asc().nullsFirst(), parcel.UPDATED_AT.asc().nullsFirst(), parcel.CADASTRAL_CODE.asc())
            .limit(limit)
            .fetch(record -> new ParcelRef(record.get(parcel.ORGANIZATION_ID), record.get(parcel.CADASTRAL_CODE)));
    }

    boolean sourceIsFresh(UUID orgId, String cadastralCode, String registry, Instant cutoff) {
        return dsl.fetchExists(selectOne().from(table("registry_sync_log"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("cadastral_code"), String.class).eq(cadastralCode))
                .and(field(name("registry_name"), String.class).eq(registry))
                .and(field(name("status"), String.class).eq("SUCCESS"))
                .and(field(name("created_at"), Instant.class).greaterOrEqual(cutoff))));
    }

    private static <T> T nvl(T value, T fallback) {
        return value == null ? fallback : value;
    }

    record ParcelRef(UUID organizationId, String cadastralCode) { }
    record ParcelOwnerRef(UUID parcelId, List<UUID> ownerIds) { }
}
