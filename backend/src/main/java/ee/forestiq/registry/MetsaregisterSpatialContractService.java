package ee.forestiq.registry;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Explicit spatial boundary contract for Metsaregister integrations.
 * Provider/source coordinates are interpreted only from declared interface
 * metadata; coordinate magnitude is used for sanity validation, never CRS
 * discovery.
 */
@Service
public class MetsaregisterSpatialContractService {
    public static final String CANONICAL_STORAGE_CRS = "EPSG:3301";
    public static final String PRESENTATION_CRS = "EPSG:3857";
    public static final String SOURCE_AXIS_ORDER = "EAST_NORTH";

    private static final Map<String, SourceContract> SOURCE_CONTRACTS = Map.of(
        "WFS", new SourceContract(CANONICAL_STORAGE_CRS, SOURCE_AXIS_ORDER),
        "WMS", new SourceContract(CANONICAL_STORAGE_CRS, SOURCE_AXIS_ORDER),
        "WMTS", new SourceContract(CANONICAL_STORAGE_CRS, SOURCE_AXIS_ORDER)
    );

    // Broad envelopes intentionally include a margin around Estonia so edge tiles remain valid.
    private static final Envelope ESTONIA_3301 = new Envelope(300_000, 6_300_000, 850_000, 6_750_000);
    private static final Envelope ESTONIA_3857 = new Envelope(2_300_000, 7_650_000, 3_300_000, 8_650_000);

    private final MeterRegistry meterRegistry;

    public MetsaregisterSpatialContractService(MetsaregisterLayerManifestService manifest, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        validateManifest(manifest);
    }

    public MapRequest normalizeMapRequest(MetsaregisterLayerManifestService.Layer layer,
                                          String crs,
                                          String bbox) {
        String normalizedCrs = crs == null ? PRESENTATION_CRS : crs.trim().toUpperCase(Locale.ROOT);
        if (!normalizedCrs.equals(PRESENTATION_CRS) && !normalizedCrs.equals(layer.sourceCrs())) {
            reject(layer.key(), "unexpected_crs", "Unsupported map CRS: " + normalizedCrs);
        }
        double[] values = parseBbox(bbox, layer.key());
        Envelope allowed = normalizedCrs.equals(CANONICAL_STORAGE_CRS) ? ESTONIA_3301 : ESTONIA_3857;
        Envelope requested = new Envelope(values[0], values[1], values[2], values[3]);
        if (!requested.intersects(allowed)) {
            reject(layer.key(), "implausible_bbox",
                "bbox does not intersect the Estonia envelope for " + normalizedCrs
                    + "; possible axis-order/CRS/double-transform error");
        }
        return new MapRequest(
            normalizedCrs,
            String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", values[0], values[1], values[2], values[3]));
    }

    public SourceContract contractForInterface(String sourceInterface) {
        SourceContract contract = SOURCE_CONTRACTS.get(sourceInterface.toUpperCase(Locale.ROOT));
        if (contract == null) throw new IllegalArgumentException("Unknown Metsaregister source interface: " + sourceInterface);
        return contract;
    }

    private void validateManifest(MetsaregisterLayerManifestService manifest) {
        for (var layer : manifest.layers()) {
            if (!layer.enabled()) continue;
            for (String sourceInterface : layer.sourceInterfaces()) {
                SourceContract contract = SOURCE_CONTRACTS.get(sourceInterface.toUpperCase(Locale.ROOT));
                if (contract == null) {
                    reject(layer.key(), "unknown_interface", "No spatial contract for source interface " + sourceInterface);
                }
                if (!contract.sourceCrs().equalsIgnoreCase(layer.sourceCrs())) {
                    reject(layer.key(), "source_crs_drift",
                        "Layer " + layer.key() + " declares " + layer.sourceCrs() + " but " + sourceInterface
                            + " is contracted as " + contract.sourceCrs());
                }
            }
        }
    }

    private double[] parseBbox(String bbox, String layerKey) {
        if (bbox == null || bbox.isBlank()) reject(layerKey, "missing_bbox", "bbox is required");
        String[] parts = bbox.split(",");
        if (parts.length != 4) reject(layerKey, "invalid_bbox", "bbox must contain four coordinates");
        double[] values = new double[4];
        for (int index = 0; index < 4; index++) {
            try {
                values[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException exception) {
                reject(layerKey, "invalid_bbox", "bbox contains a non-numeric coordinate");
            }
            if (!Double.isFinite(values[index])) reject(layerKey, "invalid_bbox", "bbox coordinate must be finite");
        }
        if (values[0] >= values[2] || values[1] >= values[3]) {
            reject(layerKey, "axis_order", "bbox min coordinates must be smaller than max coordinates");
        }
        return values;
    }

    private void reject(String layerKey, String reason, String message) {
        meterRegistry.counter("forestiq.metsaregister.spatial_contract_rejections",
            "layer", layerKey,
            "reason", reason).increment();
        throw new IllegalArgumentException(message);
    }

    public record SourceContract(String sourceCrs, String axisOrder) { }
    public record MapRequest(String crs, String bbox) { }

    private record Envelope(double minX, double minY, double maxX, double maxY) {
        boolean intersects(Envelope other) {
            return maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY;
        }
    }
}
