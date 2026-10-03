package ee.forestiq.registry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single policy authority for how ForestIQ consumes Metsaregister layers.
 * Capability discovery is informative only: a provider-advertised layer never
 * becomes locally ingested until it is explicitly declared LOCAL_DOMAIN_DATA.
 */
@Service
public class MetsaregisterLayerManifestService {
    private static final String RESOURCE = "metsaregister-layer-manifest.json";

    private final Manifest manifest;
    private final Map<String, Layer> byKey;

    public MetsaregisterLayerManifestService(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.manifest = objectMapper.readValue(input, Manifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load " + RESOURCE, exception);
        }
        validate(manifest);
        this.byKey = manifest.layers().stream()
            .collect(Collectors.toUnmodifiableMap(Layer::key, Function.identity()));
    }

    public List<Layer> layers() {
        return manifest.layers().stream()
            .sorted(Comparator.comparingInt(Layer::order).thenComparing(Layer::key))
            .toList();
    }

    public Layer requireLayer(String key) {
        Layer layer = byKey.get(key);
        if (layer == null) throw new IllegalArgumentException("Layer is not approved by the Metsaregister manifest: " + key);
        return layer;
    }

    public boolean isLocalDomainLayer(String key) {
        Layer layer = byKey.get(key);
        return layer != null && layer.enabled() && layer.primaryMode() == PrimaryMode.LOCAL_DOMAIN_DATA;
    }

    public Set<String> approvedLocalLayerNames() {
        return layers().stream()
            .filter(Layer::enabled)
            .filter(layer -> layer.primaryMode() == PrimaryMode.LOCAL_DOMAIN_DATA)
            .map(Layer::key)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Map-facing provider presentations. ForestIQ local MVT can override these via the tile catalog. */
    public List<Layer> mapLayers() {
        return layers().stream()
            .filter(Layer::enabled)
            .filter(layer -> layer.mapPresentationMode() != MapPresentationMode.NONE)
            .filter(layer -> layer.primaryMode() != PrimaryMode.DISABLED)
            .toList();
    }

    public String version() {
        return manifest.version();
    }

    private static void validate(Manifest value) {
        if (value == null || blank(value.version()) || value.layers() == null || value.layers().isEmpty()) {
            throw new IllegalStateException("Metsaregister manifest must have version and layers");
        }
        Set<String> keys = new HashSet<>();
        for (Layer layer : value.layers()) {
            if (layer == null || blank(layer.key()) || blank(layer.title())) {
                throw new IllegalStateException("Every Metsaregister layer needs key and title");
            }
            if (!keys.add(layer.key())) {
                throw new IllegalStateException("Duplicate Metsaregister layer key: " + layer.key());
            }
            if (layer.primaryMode() == null || layer.disposition() == null || layer.mapPresentationMode() == null) {
                throw new IllegalStateException("Layer policy is incomplete: " + layer.key());
            }
            if (layer.sourceInterfaces() == null || layer.sourceInterfaces().isEmpty() || blank(layer.sourceCrs())) {
                throw new IllegalStateException("Layer source metadata is incomplete: " + layer.key());
            }
            if (layer.primaryMode() == PrimaryMode.LOCAL_DOMAIN_DATA
                && layer.disposition() != MigrationDisposition.KEEP_LOCAL) {
                throw new IllegalStateException("LOCAL_DOMAIN_DATA must be KEEP_LOCAL: " + layer.key());
            }
            if (layer.disposition() == MigrationDisposition.KEEP_LOCAL
                && layer.primaryMode() != PrimaryMode.LOCAL_DOMAIN_DATA) {
                throw new IllegalStateException("Only LOCAL_DOMAIN_DATA may authorize local ingestion: " + layer.key());
            }
            if (layer.mapPresentationMode() == MapPresentationMode.WMS
                && layer.sourceInterfaces().stream().noneMatch(v -> "WMS".equals(v.toUpperCase(Locale.ROOT)))) {
                throw new IllegalStateException("WMS map layer does not declare WMS source: " + layer.key());
            }
            if (layer.mapPresentationMode() == MapPresentationMode.WMTS
                && layer.sourceInterfaces().stream().noneMatch(v -> "WMTS".equals(v.toUpperCase(Locale.ROOT)))) {
                throw new IllegalStateException("WMTS map layer does not declare WMTS source: " + layer.key());
            }
            if (layer.minZoom() < 0 || layer.maxZoom() < layer.minZoom() || layer.maxZoom() > 24) {
                throw new IllegalStateException("Invalid zoom policy: " + layer.key());
            }
            if (layer.defaultOpacity() < 0.0 || layer.defaultOpacity() > 1.0) {
                throw new IllegalStateException("Invalid opacity policy: " + layer.key());
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum PrimaryMode {
        EXTERNAL_MAP,
        BOUNDED_QUERY,
        LOCAL_DOMAIN_DATA,
        FORESTIQ_DERIVED_MAP,
        DISABLED
    }

    public enum MigrationDisposition {
        KEEP_LOCAL,
        EXTERNAL_MAP,
        BOUNDED_QUERY,
        DERIVED_ONLY,
        RETIRE
    }

    public enum MapPresentationMode {
        WMS,
        WMTS,
        NONE
    }

    public record Manifest(String version, String provider, List<Layer> layers) { }

    public record Layer(
        String key,
        String title,
        String businessUseCase,
        PrimaryMode primaryMode,
        MapPresentationMode mapPresentationMode,
        List<String> sourceInterfaces,
        String sourceCrs,
        String freshnessTarget,
        String localPersistenceJustification,
        int minZoom,
        int maxZoom,
        double defaultOpacity,
        int order,
        String attribution,
        MigrationDisposition disposition,
        List<String> affectedAssets,
        boolean enabled
    ) { }
}
