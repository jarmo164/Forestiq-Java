package ee.forestiq.geospatial;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ee.forestiq.cadastre.MapTileService;
import ee.forestiq.geospatial.etak.EtakAdminLayerManifestService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/** Bounded parcel facts derived from current published reference generations. */
@Service
public class ParcelGeospatialContextService {
    private final MapTileService mapTiles;
    private final DSLContext dsl;
    private final EtakAdminLayerManifestService manifest;

    public ParcelGeospatialContextService(MapTileService mapTiles,
                                          DSLContext dsl,
                                          EtakAdminLayerManifestService manifest) {
        this.mapTiles = mapTiles;
        this.dsl = dsl;
        this.manifest = manifest;
    }

    public ParcelContext context(String cadastralCode) {
        // Reuse the existing organization + assignment visibility boundary before spatial reads.
        mapTiles.landParcelSummary(cadastralCode);

        Record parcel = dsl.fetchOne("""
            SELECT ST_Area(p.geom)::double precision AS area_m2
              FROM geospatial_cadastre_parcel p
              JOIN geospatial_source_current c
                ON c.source_key = 'MARU_CADASTRE' AND c.generation_id = p.generation_id
             WHERE p.cadastral_code = ?
             LIMIT 1
            """, cadastralCode);
        double parcelArea = parcel == null || parcel.get("area_m2", Double.class) == null
            ? 0d : parcel.get("area_m2", Double.class);

        List<LayerFact> layerFacts = new ArrayList<>();
        for (EtakAdminLayerManifestService.Layer layer : manifest.enabledLayers()) {
            if ("ADMIN".equals(layer.datasetFamily())) continue;
            if (!layer.analysisUse() && !layer.mapVisible()) continue;
            layerFacts.add(layerFact(cadastralCode, layer, parcelArea));
        }

        OverlapFact woody = layerFacts.stream()
            .filter(fact -> fact.sourceKey().equals("MARU_ETAK_WOODY") && fact.available())
            .findFirst()
            .map(fact -> new OverlapFact(
                fact.overlapAreaHa() == null ? 0d : fact.overlapAreaHa(),
                fact.overlapPercent() == null ? 0d : fact.overlapPercent(),
                fact.provenance(),
                fact.caveat()))
            .orElse(null);
        AdministrativeFact county = administrative(cadastralCode, "MARU_ADMIN_COUNTY", "mnimi", "mkood");
        AdministrativeFact municipality = administrative(cadastralCode, "MARU_ADMIN_MUNICIPALITY", "onimi", "okood");
        AdministrativeFact settlement = administrative(cadastralCode, "MARU_ADMIN_SETTLEMENT", "animi", "akood");
        return new ParcelContext(cadastralCode, woody, county, municipality, settlement, List.copyOf(layerFacts));
    }

    private LayerFact layerFact(String cadastralCode,
                                EtakAdminLayerManifestService.Layer layer,
                                double parcelArea) {
        Record generation = currentGeneration(layer.sourceKey());
        if (generation == null) {
            return new LayerFact(
                layer.sourceKey(), layer.title(), layer.datasetFamily(), layer.geometryType(), false,
                null, null, null, null, null, layer.caveat(), layer.mapVisible());
        }
        UUID generationId = generation.get("id", UUID.class);
        String geometryType = layer.geometryType().toUpperCase();
        if ("POLYGON".equals(geometryType)) {
            Record record = dsl.fetchOne("""
                SELECT count(f.source_key)::bigint AS feature_count,
                       COALESCE(sum(ST_Area(ST_Intersection(p.geom, f.geom))), 0)::double precision AS overlap_m2
                  FROM geospatial_cadastre_parcel p
                  JOIN geospatial_source_current pc
                    ON pc.source_key = 'MARU_CADASTRE' AND pc.generation_id = p.generation_id
                  LEFT JOIN geospatial_feature f
                    ON f.source_key = ? AND f.generation_id = ?
                   AND f.geom && p.geom
                   AND ST_Intersects(f.geom, p.geom)
                 WHERE p.cadastral_code = ?
                """, layer.sourceKey(), generationId, cadastralCode);
            long featureCount = number(record, "feature_count", 0L);
            double overlapM2 = decimal(record, "overlap_m2", 0d);
            double percent = parcelArea <= 0d ? 0d : Math.min(100d, overlapM2 * 100d / parcelArea);
            return new LayerFact(
                layer.sourceKey(), layer.title(), layer.datasetFamily(), geometryType, true,
                featureCount, overlapM2 / 10_000d, percent, null,
                provenance(layer.sourceKey(), generation), layer.caveat(), layer.mapVisible());
        }
        if ("LINE".equals(geometryType)) {
            Record record = dsl.fetchOne("""
                SELECT count(f.source_key)::bigint AS feature_count,
                       COALESCE(sum(ST_Length(ST_Intersection(p.geom, f.geom))), 0)::double precision AS intersection_length_m
                  FROM geospatial_cadastre_parcel p
                  JOIN geospatial_source_current pc
                    ON pc.source_key = 'MARU_CADASTRE' AND pc.generation_id = p.generation_id
                  LEFT JOIN geospatial_feature f
                    ON f.source_key = ? AND f.generation_id = ?
                   AND f.geom && p.geom
                   AND ST_Intersects(f.geom, p.geom)
                 WHERE p.cadastral_code = ?
                """, layer.sourceKey(), generationId, cadastralCode);
            return new LayerFact(
                layer.sourceKey(), layer.title(), layer.datasetFamily(), geometryType, true,
                number(record, "feature_count", 0L), null, null,
                decimal(record, "intersection_length_m", 0d),
                provenance(layer.sourceKey(), generation), layer.caveat(), layer.mapVisible());
        }
        return new LayerFact(
            layer.sourceKey(), layer.title(), layer.datasetFamily(), geometryType, true,
            null, null, null, null, provenance(layer.sourceKey(), generation), layer.caveat(), layer.mapVisible());
    }

    private AdministrativeFact administrative(String cadastralCode,
                                               String sourceKey,
                                               String nameField,
                                               String codeField) {
        Record generation = currentGeneration(sourceKey);
        if (generation == null) return null;
        UUID generationId = generation.get("id", UUID.class);
        Record record = dsl.fetchOne(("""
            SELECT f.properties ->> '%s' AS unit_name,
                   f.properties ->> '%s' AS unit_code
              FROM geospatial_cadastre_parcel p
              JOIN geospatial_source_current pc
                ON pc.source_key = 'MARU_CADASTRE' AND pc.generation_id = p.generation_id
              JOIN geospatial_feature f
                ON f.source_key = ? AND f.generation_id = ?
               AND f.geom && ST_PointOnSurface(p.geom)
               AND ST_Covers(f.geom, ST_PointOnSurface(p.geom))
             WHERE p.cadastral_code = ?
             ORDER BY ST_Area(f.geom) ASC
             LIMIT 1
            """).formatted(nameField, codeField), sourceKey, generationId, cadastralCode);
        if (record == null) return null;
        return new AdministrativeFact(
            record.get("unit_name", String.class),
            record.get("unit_code", String.class),
            provenance(sourceKey, generation));
    }

    private Record currentGeneration(String sourceKey) {
        return dsl.fetchOne("""
            SELECT g.id,
                   g.source_date,
                   g.source_version,
                   g.artifact_checksum,
                   g.published_at,
                   s.freshness_target_seconds,
                   (SELECT r.completed_at
                      FROM geospatial_source_run r
                     WHERE r.source_key = c.source_key AND r.status = 'SUCCESS'
                     ORDER BY r.completed_at DESC NULLS LAST
                     LIMIT 1) AS latest_success_at,
                   (SELECT r.status
                      FROM geospatial_source_run r
                     WHERE r.source_key = c.source_key
                     ORDER BY r.created_at DESC
                     LIMIT 1) AS latest_run_status
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
              JOIN geospatial_source s ON s.source_key = c.source_key
             WHERE c.source_key = ? AND g.status = 'PUBLISHED'
            """, sourceKey);
    }

    private static Provenance provenance(String sourceKey, Record generation) {
        return new Provenance(
            sourceKey,
            generation.get("id", UUID.class),
            generation.get("source_date", LocalDate.class),
            generation.get("source_version", String.class),
            generation.get("artifact_checksum", String.class),
            freshness(generation));
    }

    private static String freshness(Record generation) {
        OffsetDateTime latestSuccessAt = generation.get("latest_success_at", OffsetDateTime.class);
        if (latestSuccessAt == null) return "UNKNOWN";
        Number targetValue = generation.get("freshness_target_seconds", Number.class);
        Long target = targetValue == null ? null : targetValue.longValue();
        long ageSeconds = Math.max(0L, Duration.between(latestSuccessAt, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds());
        String latestStatus = generation.get("latest_run_status", String.class);
        if ("FAILED".equals(latestStatus) || "PARTIAL".equals(latestStatus)) return "DEGRADED";
        if (target != null && ageSeconds > target) return "STALE";
        return "FRESH";
    }

    private static long number(Record record, String field, long fallback) {
        if (record == null) return fallback;
        Number value = record.get(field, Number.class);
        return value == null ? fallback : value.longValue();
    }

    private static double decimal(Record record, String field, double fallback) {
        if (record == null) return fallback;
        Number value = record.get(field, Number.class);
        return value == null ? fallback : value.doubleValue();
    }

    public record ParcelContext(String cadastralCode,
                                OverlapFact woodyVegetation,
                                AdministrativeFact county,
                                AdministrativeFact municipality,
                                AdministrativeFact settlement,
                                List<LayerFact> layerFacts) { }

    public record LayerFact(String sourceKey,
                            String title,
                            String datasetFamily,
                            String geometryType,
                            boolean available,
                            Long featureCount,
                            Double overlapAreaHa,
                            Double overlapPercent,
                            Double intersectionLengthMeters,
                            Provenance provenance,
                            String caveat,
                            boolean mapVisible) { }

    public record OverlapFact(double areaHa, double percent, Provenance provenance, String caveat) { }
    public record AdministrativeFact(String name, String code, Provenance provenance) { }
    public record Provenance(String sourceKey, UUID generationId, LocalDate sourceDate,
                             String sourceVersion, String checksum, String freshnessState) { }
}
