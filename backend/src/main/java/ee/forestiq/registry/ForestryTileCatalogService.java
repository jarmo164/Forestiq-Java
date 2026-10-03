package ee.forestiq.registry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Checked-in, testable MVT contract for ForestIQ-owned forestry map layers. */
@Service
class ForestryTileCatalogService {
    private static final String RESOURCE = "forestry-tile-catalog.json";

    private final Catalog catalog;
    private final Map<String, Layer> byKey;

    ForestryTileCatalogService(ObjectMapper objectMapper, MetsaregisterLayerManifestService manifest) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.catalog = objectMapper.readValue(input, Catalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load " + RESOURCE, exception);
        }
        validate(catalog, manifest);
        this.byKey = catalog.layers().stream()
            .collect(Collectors.toUnmodifiableMap(Layer::key, Function.identity()));
    }

    String version() {
        return catalog.version();
    }

    String sourceLayer() {
        return catalog.sourceLayer();
    }

    List<Layer> layers() {
        return catalog.layers().stream()
            .sorted(Comparator.comparingInt(Layer::minZoom).thenComparing(Layer::key))
            .toList();
    }

    Layer find(String key) {
        return byKey.get(key);
    }

    Layer require(String key) {
        Layer layer = find(key);
        if (layer == null) throw new IllegalArgumentException("Layer has no approved forestry tile contract: " + key);
        return layer;
    }

    private static void validate(Catalog value, MetsaregisterLayerManifestService manifest) {
        if (value == null || blank(value.version()) || blank(value.sourceLayer()) || value.layers() == null) {
            throw new IllegalStateException("Forestry tile catalog requires version, sourceLayer and layers");
        }
        Set<String> keys = new HashSet<>();
        for (Layer layer : value.layers()) {
            if (layer == null || blank(layer.key()) || !keys.add(layer.key())) {
                throw new IllegalStateException("Forestry tile catalog contains a missing/duplicate key");
            }
            if (!manifest.isLocalDomainLayer(layer.key())) {
                throw new IllegalStateException("MVT catalog may contain only LOCAL_DOMAIN_DATA layers: " + layer.key());
            }
            if (layer.minZoom() < 0 || layer.maxZoom() < layer.minZoom() || layer.maxZoom() > 24) {
                throw new IllegalStateException("Invalid forestry tile zoom range: " + layer.key());
            }
            if (layer.featureBudget() <= 0 || layer.byteBudget() <= 0 || layer.extent() <= 0 || layer.buffer() < 0) {
                throw new IllegalStateException("Invalid forestry tile budget: " + layer.key());
            }
            if (layer.coldP95Ms() <= 0 || layer.warmP95Ms() <= 0 || layer.databaseP95Ms() <= 0
                || layer.warmP95Ms() > layer.coldP95Ms() || layer.databaseP95Ms() > layer.coldP95Ms()) {
                throw new IllegalStateException("Invalid forestry tile latency budget: " + layer.key());
            }
            if (!"HIDDEN_BELOW_MIN_ZOOM".equals(layer.lowZoomPolicy())) {
                throw new IllegalStateException("Unsupported low zoom policy: " + layer.key());
            }
            if (blank(layer.authorizationScope()) || blank(layer.styleKind()) || layer.properties() == null) {
                throw new IllegalStateException("Incomplete forestry tile frontend contract: " + layer.key());
            }
            Set<String> allowed = Set.of("featureKey", "cadastralCode", "notificationKey", "providerStatus", "archived",
                "compartmentId", "code", "cause", "severity");
            if (!allowed.containsAll(layer.properties())) {
                throw new IllegalStateException("Forestry tile exposes a non-approved property: " + layer.key());
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record Catalog(String version, String sourceLayer, List<Layer> layers) { }

    record Layer(
        String key,
        int minZoom,
        int maxZoom,
        int featureBudget,
        int byteBudget,
        int coldP95Ms,
        int warmP95Ms,
        int databaseP95Ms,
        int extent,
        int buffer,
        String lowZoomPolicy,
        boolean queryable,
        String authorizationScope,
        String styleKind,
        List<String> properties
    ) { }
}
