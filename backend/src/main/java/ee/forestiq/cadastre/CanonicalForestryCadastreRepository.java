package ee.forestiq.cadastre;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationDto;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationPage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureDto;
import ee.forestiq.cadastre.CadastreDtos.ForestFeaturePage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureRawDto;
import ee.forestiq.registry.MetsaregisterForestryProjectionService;

/**
 * Canonical forestry read adapter for the existing cadastre API contract.
 *
 * <p>Before the first canonical forestry publication the inherited legacy reads
 * remain available for bootstrap compatibility. Once a current canonical
 * generation exists, all notification/compartment reads stay on that generation;
 * an empty canonical result never silently falls back to tenant mirror data.</p>
 */
@Primary
@Repository
public class CanonicalForestryCadastreRepository extends CadastreRepository {
    private static final int COMPATIBILITY_LIST_LIMIT = 100;

    private final DSLContext dsl;

    public CanonicalForestryCadastreRepository(DSLContext dsl) {
        super(dsl);
        this.dsl = dsl;
    }

    @Override
    public List<CadastreNotificationDto> findNotifications(UUID orgId, String cadastralCode, int limit) {
        if (!canonicalAvailable()) return super.findNotifications(orgId, cadastralCode, limit);
        return canonicalNotificationRows(orgId, cadastralCode, null, limit);
    }

    @Override
    public CadastreNotificationPage findNotificationPage(
        UUID orgId,
        String cadastralCode,
        Long cursor,
        int limit
    ) {
        if (!canonicalAvailable()) return super.findNotificationPage(orgId, cadastralCode, cursor, limit);
        List<CadastreNotificationDto> rows = canonicalNotificationRows(orgId, cadastralCode, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<CadastreNotificationDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new CadastreNotificationPage(items, nextCursor, hasMore);
    }

    @Override
    public List<ForestFeatureDto> findForestFeatures(UUID orgId, String cadastralCode) {
        if (!canonicalAvailable()) return super.findForestFeatures(orgId, cadastralCode);
        return canonicalForestRows(orgId, cadastralCode, null, COMPATIBILITY_LIST_LIMIT);
    }

    @Override
    public ForestFeaturePage findForestFeaturePage(
        UUID orgId,
        String cadastralCode,
        Long cursor,
        int limit
    ) {
        if (!canonicalAvailable()) return super.findForestFeaturePage(orgId, cadastralCode, cursor, limit);
        List<ForestFeatureDto> rows = canonicalForestRows(orgId, cadastralCode, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ForestFeatureDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new ForestFeaturePage(items, nextCursor, hasMore);
    }

    @Override
    public Optional<ForestFeatureRawDto> findForestFeatureRaw(
        UUID orgId,
        String cadastralCode,
        Long featureId
    ) {
        if (!canonicalAvailable()) return super.findForestFeatureRaw(orgId, cadastralCode, featureId);
        return dsl.fetchOptional("""
            SELECT c.id,
                   c.attributes::text AS attributes,
                   CASE WHEN c.geom IS NULL THEN NULL
                        ELSE ST_AsGeoJSON(ST_Transform(c.geom, 4326))
                   END AS geometry
              FROM forestry_current_compartment c
             WHERE c.id = ?
               AND c.cadastral_code = ?
               AND EXISTS (
                   SELECT 1
                     FROM land_parcel p
                    WHERE p.organization_id = ?
                      AND p.cadastral_code = c.cadastral_code
               )
            """, featureId, cadastralCode, orgId)
            .map(record -> new ForestFeatureRawDto(
                record.get("id", Long.class),
                record.get("attributes", String.class),
                record.get("geometry", String.class)));
    }

    private boolean canonicalAvailable() {
        Boolean available = dsl.fetchOne("""
            SELECT EXISTS (
                SELECT 1
                  FROM geospatial_source_current
                 WHERE source_key = ?
            ) AS available
            """, MetsaregisterForestryProjectionService.SOURCE_KEY)
            .get("available", Boolean.class);
        return Boolean.TRUE.equals(available);
    }

    private List<CadastreNotificationDto> canonicalNotificationRows(
        UUID orgId,
        String cadastralCode,
        Long cursor,
        int limit
    ) {
        String cursorClause = cursor == null ? "" : "AND n.id < ?";
        Object[] parameters = cursor == null
            ? new Object[] { cadastralCode, orgId, limit }
            : new Object[] { cadastralCode, orgId, cursor, limit };
        return dsl.fetch("""
            SELECT n.id,
                   n.cadastral_code,
                   n.notification_number,
                   n.amount_to_be_cut,
                   n.area,
                   n.cadastre_subpart_code,
                   n.damage_code,
                   n.confirmation_date,
                   n.registration_date,
                   n.decision,
                   n.state,
                   n.work_code,
                   n.archived,
                   n.archive_date
              FROM forestry_current_notification n
             WHERE n.cadastral_code = ?
               AND EXISTS (
                   SELECT 1
                     FROM land_parcel p
                    WHERE p.organization_id = ?
                      AND p.cadastral_code = n.cadastral_code
               )
            """ + cursorClause + """
             ORDER BY n.id DESC
             LIMIT ?
            """, parameters).map(this::toCanonicalNotification);
    }

    private List<ForestFeatureDto> canonicalForestRows(
        UUID orgId,
        String cadastralCode,
        Long cursor,
        int limit
    ) {
        String cursorClause = cursor == null ? "" : "AND c.id < ?";
        Object[] parameters = cursor == null
            ? new Object[] { cadastralCode, orgId, limit }
            : new Object[] { cadastralCode, orgId, cursor, limit };
        return dsl.fetch("""
            SELECT c.id,
                   c.cadastral_code,
                   c.source_id,
                   c.subpart_code,
                   c.title,
                   c.work_code,
                   c.decision,
                   c.area,
                   c.volume,
                   c.event_date
              FROM forestry_current_compartment c
             WHERE c.cadastral_code = ?
               AND EXISTS (
                   SELECT 1
                     FROM land_parcel p
                    WHERE p.organization_id = ?
                      AND p.cadastral_code = c.cadastral_code
               )
            """ + cursorClause + """
             ORDER BY c.id DESC
             LIMIT ?
            """, parameters).map(this::toCanonicalForestFeature);
    }

    private CadastreNotificationDto toCanonicalNotification(Record record) {
        return new CadastreNotificationDto(
            record.get("id", Long.class),
            record.get("cadastral_code", String.class),
            record.get("notification_number", String.class),
            doubleValue(record.get("amount_to_be_cut", BigDecimal.class)),
            doubleValue(record.get("area", BigDecimal.class)),
            record.get("cadastre_subpart_code", Long.class),
            record.get("damage_code", String.class),
            record.get("confirmation_date", Long.class),
            record.get("registration_date", Long.class),
            record.get("decision", String.class),
            record.get("state", String.class),
            record.get("work_code", String.class),
            Boolean.TRUE.equals(record.get("archived", Boolean.class)),
            record.get("archive_date", Long.class));
    }

    private ForestFeatureDto toCanonicalForestFeature(Record record) {
        Long subpart = record.get("subpart_code", Long.class);
        Integer subpartCode = subpart == null || subpart > Integer.MAX_VALUE || subpart < Integer.MIN_VALUE
            ? null
            : subpart.intValue();
        return new ForestFeatureDto(
            record.get("id", Long.class),
            record.get("cadastral_code", String.class),
            "METSAREGISTER_ERALDISED",
            record.get("source_id", String.class),
            subpartCode,
            record.get("title", String.class),
            record.get("work_code", String.class),
            record.get("decision", String.class),
            doubleValue(record.get("area", BigDecimal.class)),
            doubleValue(record.get("volume", BigDecimal.class)),
            record.get("event_date", Long.class),
            null,
            null);
    }

    private static Double doubleValue(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
