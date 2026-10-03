package ee.forestiq.cadastre;

import static ee.forestiq.jooq.Tables.CADASTRE_LABEL;
import static ee.forestiq.jooq.Tables.CADASTRE_NOTIFICATION;
import static ee.forestiq.jooq.Tables.CADASTRE_SUB_PART;
import static ee.forestiq.jooq.Tables.FOREST_REGISTRY_FEATURE;
import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.OWNER;
import static org.jooq.impl.DSL.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationDto;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationPage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureDto;
import ee.forestiq.cadastre.CadastreDtos.ForestFeaturePage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureRawDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartGeometryDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartPage;
import ee.forestiq.owner.OwnerDtos.CadastreEvaluation;
import ee.forestiq.owner.OwnerDtos.SaveEvaluationRequest;

@Repository
public class CadastreRepository {

    private static final int COMPATIBILITY_LIST_LIMIT = 100;

    private final DSLContext dsl;

    public CadastreRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ParcelAssignmentScope findAssignmentScope(UUID orgId, String cadastralCode) {
        boolean exists = dsl.fetchExists(
            selectOne().from(LAND_PARCEL)
                .where(LAND_PARCEL.CADASTRAL_CODE.eq(cadastralCode)
                    .and(LAND_PARCEL.ORGANIZATION_ID.eq(orgId))));
        if (!exists) return new ParcelAssignmentScope(false, List.of());

        var relation = table(name("owner_parcel_relation")).as("opr");
        var relationOwnerId = field(name("opr", "owner_id"), UUID.class);
        var relationParcelId = field(name("opr", "parcel_id"), UUID.class);
        var relationOrgId = field(name("opr", "organization_id"), UUID.class);
        var relationActive = field(name("opr", "active"), Boolean.class);

        List<String> assignedSubjects = dsl.select(OWNER.ASSIGNED_TO_SUBJECT)
            .from(LAND_PARCEL)
            .join(relation).on(relationParcelId.eq(LAND_PARCEL.ID)
                .and(relationOrgId.eq(LAND_PARCEL.ORGANIZATION_ID))
                .and(relationActive.isTrue()))
            .join(OWNER).on(OWNER.ID.eq(relationOwnerId)
                .and(OWNER.ORGANIZATION_ID.eq(relationOrgId)))
            .where(LAND_PARCEL.CADASTRAL_CODE.eq(cadastralCode)
                .and(LAND_PARCEL.ORGANIZATION_ID.eq(orgId)))
            .fetch(OWNER.ASSIGNED_TO_SUBJECT);
        return new ParcelAssignmentScope(true, assignedSubjects);
    }

    public List<String> findLabels(UUID orgId, String cadastralCode) {
        return dsl.select(CADASTRE_LABEL.LABEL)
            .from(CADASTRE_LABEL)
            .where(CADASTRE_LABEL.CADASTRAL_CODE.eq(cadastralCode)
                .and(CADASTRE_LABEL.ORGANIZATION_ID.eq(orgId)))
            .fetchInto(String.class);
    }

    public void addLabel(UUID orgId, String cadastralCode, String label) {
        dsl.insertInto(CADASTRE_LABEL)
            .columns(CADASTRE_LABEL.CADASTRAL_CODE, CADASTRE_LABEL.ORGANIZATION_ID, CADASTRE_LABEL.LABEL)
            .values(cadastralCode, orgId, label)
            .onConflictDoNothing()
            .execute();
    }

    public void removeLabel(UUID orgId, String cadastralCode, String label) {
        dsl.deleteFrom(CADASTRE_LABEL)
            .where(CADASTRE_LABEL.CADASTRAL_CODE.eq(cadastralCode)
                .and(CADASTRE_LABEL.ORGANIZATION_ID.eq(orgId))
                .and(CADASTRE_LABEL.LABEL.eq(label)))
            .execute();
    }

    public Optional<CadastreEvaluation> findEvaluation(UUID orgId, String cadastralCode) {
        return dsl.select(LAND_PARCEL.OUR_PRICE, LAND_PARCEL.OWNERS_PRICE)
            .from(LAND_PARCEL)
            .where(LAND_PARCEL.CADASTRAL_CODE.eq(cadastralCode)
                .and(LAND_PARCEL.ORGANIZATION_ID.eq(orgId)))
            .fetchOptional(record -> new CadastreEvaluation(record.value1(), record.value2()));
    }

    public boolean saveEvaluation(UUID orgId, String cadastralCode, SaveEvaluationRequest request) {
        int updated = dsl.update(LAND_PARCEL)
            .set(LAND_PARCEL.OUR_PRICE, request.ourPrice())
            .set(LAND_PARCEL.OWNERS_PRICE, request.ownerPrice())
            .where(LAND_PARCEL.CADASTRAL_CODE.eq(cadastralCode)
                .and(LAND_PARCEL.ORGANIZATION_ID.eq(orgId)))
            .execute();
        return updated > 0;
    }

    public List<CadastreNotificationDto> findNotifications(UUID orgId, String cadastralCode, int limit) {
        return dsl.select(
                CADASTRE_NOTIFICATION.ID,
                CADASTRE_NOTIFICATION.CADASTRAL_CODE,
                CADASTRE_NOTIFICATION.NOTIFICATION_NUMBER,
                CADASTRE_NOTIFICATION.AMOUNT_TO_BE_CUT,
                CADASTRE_NOTIFICATION.AREA,
                CADASTRE_NOTIFICATION.CADASTRE_SUBPART_CODE,
                CADASTRE_NOTIFICATION.DAMAGE_CODE,
                CADASTRE_NOTIFICATION.CONFIRMATION_DATE,
                CADASTRE_NOTIFICATION.REGISTRATION_DATE,
                CADASTRE_NOTIFICATION.DECISION,
                CADASTRE_NOTIFICATION.STATE,
                CADASTRE_NOTIFICATION.WORK_CODE,
                CADASTRE_NOTIFICATION.ARCHIVED,
                CADASTRE_NOTIFICATION.ARCHIVE_DATE)
            .from(CADASTRE_NOTIFICATION)
            .where(CADASTRE_NOTIFICATION.ORGANIZATION_ID.eq(orgId)
                .and(CADASTRE_NOTIFICATION.CADASTRAL_CODE.eq(cadastralCode)))
            .orderBy(CADASTRE_NOTIFICATION.ID.desc())
            .limit(limit)
            .fetch(this::toNotification);
    }

    public CadastreNotificationPage findNotificationPage(UUID orgId, String cadastralCode, Long cursor, int limit) {
        Condition condition = CADASTRE_NOTIFICATION.ORGANIZATION_ID.eq(orgId)
            .and(CADASTRE_NOTIFICATION.CADASTRAL_CODE.eq(cadastralCode));
        if (cursor != null) {
            condition = condition.and(CADASTRE_NOTIFICATION.ID.lt(cursor));
        }

        List<CadastreNotificationDto> rows = dsl.select(
                CADASTRE_NOTIFICATION.ID,
                CADASTRE_NOTIFICATION.CADASTRAL_CODE,
                CADASTRE_NOTIFICATION.NOTIFICATION_NUMBER,
                CADASTRE_NOTIFICATION.AMOUNT_TO_BE_CUT,
                CADASTRE_NOTIFICATION.AREA,
                CADASTRE_NOTIFICATION.CADASTRE_SUBPART_CODE,
                CADASTRE_NOTIFICATION.DAMAGE_CODE,
                CADASTRE_NOTIFICATION.CONFIRMATION_DATE,
                CADASTRE_NOTIFICATION.REGISTRATION_DATE,
                CADASTRE_NOTIFICATION.DECISION,
                CADASTRE_NOTIFICATION.STATE,
                CADASTRE_NOTIFICATION.WORK_CODE,
                CADASTRE_NOTIFICATION.ARCHIVED,
                CADASTRE_NOTIFICATION.ARCHIVE_DATE)
            .from(CADASTRE_NOTIFICATION)
            .where(condition)
            .orderBy(CADASTRE_NOTIFICATION.ID.desc())
            .limit(limit + 1)
            .fetch(this::toNotification);

        boolean hasMore = rows.size() > limit;
        List<CadastreNotificationDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new CadastreNotificationPage(items, nextCursor, hasMore);
    }

    /** Compatibility list kept bounded; new clients should use findForestFeaturePage. */
    public List<ForestFeatureDto> findForestFeatures(UUID orgId, String cadastralCode) {
        return forestFeatureQuery(orgId, cadastralCode, null, COMPATIBILITY_LIST_LIMIT);
    }

    public ForestFeaturePage findForestFeaturePage(UUID orgId, String cadastralCode, Long cursor, int limit) {
        List<ForestFeatureDto> rows = forestFeatureQuery(orgId, cadastralCode, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ForestFeatureDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new ForestFeaturePage(items, nextCursor, hasMore);
    }

    private List<ForestFeatureDto> forestFeatureQuery(UUID orgId, String cadastralCode, Long cursor, int limit) {
        Condition condition = FOREST_REGISTRY_FEATURE.ORGANIZATION_ID.eq(orgId)
            .and(FOREST_REGISTRY_FEATURE.CADASTRAL_CODE.eq(cadastralCode));
        if (cursor != null) {
            condition = condition.and(FOREST_REGISTRY_FEATURE.ID.lt(cursor));
        }
        return dsl.select(
                FOREST_REGISTRY_FEATURE.ID,
                FOREST_REGISTRY_FEATURE.CADASTRAL_CODE,
                FOREST_REGISTRY_FEATURE.SOURCE_LAYER,
                FOREST_REGISTRY_FEATURE.SOURCE_ID,
                FOREST_REGISTRY_FEATURE.SUBPART_CODE,
                FOREST_REGISTRY_FEATURE.TITLE,
                FOREST_REGISTRY_FEATURE.WORK_CODE,
                FOREST_REGISTRY_FEATURE.DECISION,
                FOREST_REGISTRY_FEATURE.AREA,
                FOREST_REGISTRY_FEATURE.VOLUME,
                FOREST_REGISTRY_FEATURE.EVENT_DATE)
            .from(FOREST_REGISTRY_FEATURE)
            .where(condition)
            .orderBy(FOREST_REGISTRY_FEATURE.ID.desc())
            .limit(limit)
            .fetch(record -> new ForestFeatureDto(
                record.value1(),
                record.value2(),
                record.value3(),
                record.value4(),
                record.value5(),
                record.value6(),
                record.value7(),
                record.value8(),
                doubleValue(record.value9()),
                doubleValue(record.value10()),
                record.value11(),
                null,
                null));
    }

    public Optional<ForestFeatureRawDto> findForestFeatureRaw(UUID orgId, String cadastralCode, Long featureId) {
        return dsl.select(
                FOREST_REGISTRY_FEATURE.ID,
                FOREST_REGISTRY_FEATURE.ATTRIBUTES,
                FOREST_REGISTRY_FEATURE.GEOMETRY)
            .from(FOREST_REGISTRY_FEATURE)
            .where(FOREST_REGISTRY_FEATURE.ORGANIZATION_ID.eq(orgId)
                .and(FOREST_REGISTRY_FEATURE.CADASTRAL_CODE.eq(cadastralCode))
                .and(FOREST_REGISTRY_FEATURE.ID.eq(featureId)))
            .fetchOptional(record -> new ForestFeatureRawDto(
                record.value1(),
                record.value2(),
                record.value3()));
    }

    /** Compatibility list kept bounded; new clients should use findSubPartPage. */
    public List<SubPartDto> findSubParts(UUID orgId, String cadastralCode) {
        return subPartQuery(orgId, cadastralCode, null, COMPATIBILITY_LIST_LIMIT);
    }

    public SubPartPage findSubPartPage(UUID orgId, String cadastralCode, Long cursor, int limit) {
        List<SubPartDto> rows = subPartQuery(orgId, cadastralCode, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<SubPartDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new SubPartPage(items, nextCursor, hasMore);
    }

    private List<SubPartDto> subPartQuery(UUID orgId, String cadastralCode, Long cursor, int limit) {
        Condition condition = CADASTRE_SUB_PART.ORGANIZATION_ID.eq(orgId)
            .and(CADASTRE_SUB_PART.CADASTRAL_CODE.eq(cadastralCode));
        if (cursor != null) {
            condition = condition.and(CADASTRE_SUB_PART.ID.lt(cursor));
        }
        return dsl.select(
                CADASTRE_SUB_PART.ID,
                CADASTRE_SUB_PART.CADASTRAL_CODE,
                CADASTRE_SUB_PART.SUB_PART_CODE,
                CADASTRE_SUB_PART.TREE_TYPE_CODE,
                CADASTRE_SUB_PART.AREA)
            .from(CADASTRE_SUB_PART)
            .where(condition)
            .orderBy(CADASTRE_SUB_PART.ID.desc())
            .limit(limit)
            .fetch(record -> new SubPartDto(
                record.value1(),
                record.value2(),
                record.value3(),
                record.value4(),
                doubleValue(record.value5()),
                null));
    }

    public Optional<SubPartGeometryDto> findSubPartGeometry(UUID orgId, String cadastralCode, Long subPartId) {
        return dsl.select(
                CADASTRE_SUB_PART.ID,
                CADASTRE_SUB_PART.CADASTRAL_CODE,
                CADASTRE_SUB_PART.SUB_PART_CODE,
                CADASTRE_SUB_PART.POLYGON)
            .from(CADASTRE_SUB_PART)
            .where(CADASTRE_SUB_PART.ORGANIZATION_ID.eq(orgId)
                .and(CADASTRE_SUB_PART.CADASTRAL_CODE.eq(cadastralCode))
                .and(CADASTRE_SUB_PART.ID.eq(subPartId)))
            .fetchOptional(record -> new SubPartGeometryDto(
                record.value1(),
                record.value2(),
                record.value3(),
                record.value4()));
    }

    public Optional<SubPartGeometryDto> findSubPartGeometryByCode(UUID orgId, String cadastralCode, Integer subPartCode) {
        return dsl.select(
                CADASTRE_SUB_PART.ID,
                CADASTRE_SUB_PART.CADASTRAL_CODE,
                CADASTRE_SUB_PART.SUB_PART_CODE,
                CADASTRE_SUB_PART.POLYGON)
            .from(CADASTRE_SUB_PART)
            .where(CADASTRE_SUB_PART.ORGANIZATION_ID.eq(orgId)
                .and(CADASTRE_SUB_PART.CADASTRAL_CODE.eq(cadastralCode))
                .and(CADASTRE_SUB_PART.SUB_PART_CODE.eq(subPartCode)))
            .orderBy(CADASTRE_SUB_PART.ID.desc())
            .limit(1)
            .fetchOptional(record -> new SubPartGeometryDto(
                record.value1(),
                record.value2(),
                record.value3(),
                record.value4()));
    }

    private CadastreNotificationDto toNotification(Record record) {
        return new CadastreNotificationDto(
            record.get(CADASTRE_NOTIFICATION.ID),
            record.get(CADASTRE_NOTIFICATION.CADASTRAL_CODE),
            stringValue(record.get(CADASTRE_NOTIFICATION.NOTIFICATION_NUMBER)),
            doubleValue(record.get(CADASTRE_NOTIFICATION.AMOUNT_TO_BE_CUT)),
            doubleValue(record.get(CADASTRE_NOTIFICATION.AREA)),
            record.get(CADASTRE_NOTIFICATION.CADASTRE_SUBPART_CODE),
            record.get(CADASTRE_NOTIFICATION.DAMAGE_CODE),
            record.get(CADASTRE_NOTIFICATION.CONFIRMATION_DATE),
            record.get(CADASTRE_NOTIFICATION.REGISTRATION_DATE),
            record.get(CADASTRE_NOTIFICATION.DECISION),
            stringValue(record.get(CADASTRE_NOTIFICATION.STATE)),
            record.get(CADASTRE_NOTIFICATION.WORK_CODE),
            Boolean.TRUE.equals(record.get(CADASTRE_NOTIFICATION.ARCHIVED)),
            record.get(CADASTRE_NOTIFICATION.ARCHIVE_DATE));
    }

    private static Double doubleValue(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    public record ParcelAssignmentScope(boolean parcelExists, List<String> assignedSubjects) { }
}
