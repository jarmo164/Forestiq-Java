package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.OWNER_PARCEL_RELATION;
import static org.jooq.impl.DSL.field;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.Coordinates;
import ee.forestiq.owner.OwnerDtos.LandParcel;

@Repository
public class OwnerParcelRepository {

    private final DSLContext dsl;

    public OwnerParcelRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long parcelCount(UUID orgId, UUID ownerId) {
        return dsl.selectCount()
            .from(OWNER_PARCEL_RELATION)
            .where(OWNER_PARCEL_RELATION.ORGANIZATION_ID.eq(orgId)
                .and(OWNER_PARCEL_RELATION.OWNER_ID.eq(ownerId))
                .and(OWNER_PARCEL_RELATION.ACTIVE.isTrue()))
            .fetchOne(0, long.class);
    }

    @Cacheable(cacheNames = "owner-parcels", key = "#orgId.toString() + ':' + #ownerId.toString()")
    public List<LandParcel> findParcels(UUID orgId, UUID ownerId) {
        Field<Double> lng = field("ST_X(ST_Centroid({0}))", Double.class, LAND_PARCEL.BOUNDARY);
        Field<Double> lat = field("ST_Y(ST_Centroid({0}))", Double.class, LAND_PARCEL.BOUNDARY);
        return dsl.select(LAND_PARCEL.ID, LAND_PARCEL.CADASTRAL_CODE, LAND_PARCEL.AREA_HECTARES,
                lng, lat, LAND_PARCEL.NAME, LAND_PARCEL.COUNTY, LAND_PARCEL.MUNICIPALITY,
                LAND_PARCEL.PARCEL_TYPE, LAND_PARCEL.MARKED)
            .from(OWNER_PARCEL_RELATION)
            .join(LAND_PARCEL)
            .on(LAND_PARCEL.ID.eq(OWNER_PARCEL_RELATION.PARCEL_ID)
                .and(LAND_PARCEL.ORGANIZATION_ID.eq(OWNER_PARCEL_RELATION.ORGANIZATION_ID)))
            .where(OWNER_PARCEL_RELATION.ORGANIZATION_ID.eq(orgId)
                .and(OWNER_PARCEL_RELATION.OWNER_ID.eq(ownerId))
                .and(OWNER_PARCEL_RELATION.ACTIVE.isTrue()))
            .orderBy(LAND_PARCEL.CADASTRAL_CODE.asc())
            .fetch(record -> new LandParcel(
                record.get(LAND_PARCEL.ID),
                record.get(LAND_PARCEL.CADASTRAL_CODE),
                record.get(LAND_PARCEL.AREA_HECTARES),
                coordinates(record.get(lng), record.get(lat)),
                null,
                record.get(LAND_PARCEL.NAME),
                record.get(LAND_PARCEL.COUNTY),
                record.get(LAND_PARCEL.MUNICIPALITY),
                record.get(LAND_PARCEL.PARCEL_TYPE),
                Boolean.TRUE.equals(record.get(LAND_PARCEL.MARKED))));
    }

    public void unmarkAllParcels(UUID orgId, UUID ownerId) {
        dsl.update(LAND_PARCEL)
            .set(LAND_PARCEL.MARKED, false)
            .where(LAND_PARCEL.ORGANIZATION_ID.eq(orgId)
                .andExists(dsl.selectOne()
                    .from(OWNER_PARCEL_RELATION)
                    .where(OWNER_PARCEL_RELATION.ORGANIZATION_ID.eq(orgId)
                        .and(OWNER_PARCEL_RELATION.OWNER_ID.eq(ownerId))
                        .and(OWNER_PARCEL_RELATION.PARCEL_ID.eq(LAND_PARCEL.ID))
                        .and(OWNER_PARCEL_RELATION.ACTIVE.isTrue()))))
            .execute();
    }

    public void markParcels(UUID orgId, UUID ownerId, List<String> cadastralCodes) {
        dsl.update(LAND_PARCEL)
            .set(LAND_PARCEL.MARKED, true)
            .where(LAND_PARCEL.ORGANIZATION_ID.eq(orgId)
                .and(LAND_PARCEL.CADASTRAL_CODE.in(cadastralCodes))
                .andExists(dsl.selectOne()
                    .from(OWNER_PARCEL_RELATION)
                    .where(OWNER_PARCEL_RELATION.ORGANIZATION_ID.eq(orgId)
                        .and(OWNER_PARCEL_RELATION.OWNER_ID.eq(ownerId))
                        .and(OWNER_PARCEL_RELATION.PARCEL_ID.eq(LAND_PARCEL.ID))
                        .and(OWNER_PARCEL_RELATION.ACTIVE.isTrue()))))
            .execute();
    }

    static Coordinates coordinates(Double longitude, Double latitude) {
        if (longitude == null || latitude == null) return null;
        return new Coordinates(longitude, latitude);
    }
}
