package ee.forestiq.geospatial.etak;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Checked-in allow-list and business contract for ETAK/admin layers. */
@Service
public class EtakAdminLayerManifestService {
    private static final String RESOURCE = "etak-admin-layer-manifest.json";

    private final Manifest manifest;

    public EtakAdminLayerManifestService(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.manifest = objectMapper.readValue(input, Manifest.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + RESOURCE, ex);
        }
        validate(manifest);
    }

    public Manifest manifest() {
        return manifest;
    }

    public List<Layer> enabledLayers() {
        return manifest.layers().stream().filter(Layer::enabled).toList();
    }

    public List<Layer> mapLayers() {
        return manifest.layers().stream().filter(layer -> layer.enabled() && layer.mapVisible()).toList();
    }

    public Layer require(String sourceKey) {
        return manifest.layers().stream()
            .filter(layer -> layer.sourceKey().equals(sourceKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Source is not in approved geospatial manifest: " + sourceKey));
    }

    public boolean contains(String sourceKey) {
        return manifest.layers().stream().anyMatch(layer -> layer.sourceKey().equals(sourceKey));
    }

    private static void validate(Manifest value) {
        if (value == null || value.manifestVersion() == null || value.manifestVersion().isBlank()) {
            throw new IllegalStateException("Geospatial manifest version is required");
        }
        if (value.canonicalSrid() != 3301) {
            throw new IllegalStateException("ETAK/admin canonical CRS must be EPSG:3301");
        }
        if (value.layers() == null || value.layers().isEmpty()) {
            throw new IllegalStateException("Geospatial manifest must contain layers");
        }
        Set<String> keys = new HashSet<>();
        for (Layer layer : value.layers()) {
            if (layer.sourceKey() == null || !layer.sourceKey().matches("MARU_[A-Z0-9_]{2,120}")) {
                throw new IllegalStateException("Invalid manifest sourceKey: " + layer.sourceKey());
            }
            if (!keys.add(layer.sourceKey())) {
                throw new IllegalStateException("Duplicate manifest sourceKey: " + layer.sourceKey());
            }
            if (layer.title() == null || layer.title().isBlank() || layer.businessPurpose() == null || layer.businessPurpose().isBlank()) {
                throw new IllegalStateException("Manifest layer requires title and business purpose: " + layer.sourceKey());
            }
            if (layer.sourceSrid() != 3301) {
                throw new IllegalStateException("Approved ETAK/admin source must declare verified EPSG:3301: " + layer.sourceKey());
            }
            String geometry = layer.geometryType() == null ? "" : layer.geometryType().toUpperCase(Locale.ROOT);
            if (!Set.of("POLYGON", "LINE", "POINT").contains(geometry)) {
                throw new IllegalStateException("Unsupported geometryType for " + layer.sourceKey());
            }
            if (layer.identityField() == null || !layer.identityField().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                throw new IllegalStateException("Unsafe identity field for " + layer.sourceKey());
            }
            if (layer.requiredFields() == null || !layer.requiredFields().contains(layer.identityField())) {
                throw new IllegalStateException("Required fields must include identity field for " + layer.sourceKey());
            }
            if (layer.enabled()) {
                URI endpoint = URI.create(layer.endpoint());
                if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                    throw new IllegalStateException("Enabled provider endpoint must use HTTPS: " + layer.sourceKey());
                }
                if (!"WFS_PAGED".equals(layer.importMode())) {
                    throw new IllegalStateException("Enabled layer must use bounded WFS_PAGED import: " + layer.sourceKey());
                }
                if (layer.providerLayer() == null || layer.providerLayer().isBlank()) {
                    throw new IllegalStateException("Enabled layer needs providerLayer: " + layer.sourceKey());
                }
                if (layer.cadenceSeconds() <= 0 || layer.freshnessTargetSeconds() <= 0) {
                    throw new IllegalStateException("Enabled layer needs cadence/freshness thresholds: " + layer.sourceKey());
                }
                if (layer.minimumRows() < 0 || layer.maximumRows() < layer.minimumRows()) {
                    throw new IllegalStateException("Invalid expected row range for " + layer.sourceKey());
                }
            } else if (layer.disabledReason() == null || layer.disabledReason().isBlank()) {
                throw new IllegalStateException("Disabled layer must explain why it is disabled: " + layer.sourceKey());
            }
        }
    }

    public record Manifest(String manifestVersion, String provider, int canonicalSrid, List<Layer> layers) { }

    public record Layer(
        String sourceKey,
        String title,
        String datasetFamily,
        String endpoint,
        String providerLayer,
        boolean enabled,
        String disabledReason,
        String businessPurpose,
        String geometryType,
        int sourceSrid,
        String identityField,
        List<String> requiredFields,
        long cadenceSeconds,
        long freshnessTargetSeconds,
        String importMode,
        boolean mapVisible,
        boolean analysisUse,
        int minZoom,
        int maxZoom,
        double defaultOpacity,
        long minimumRows,
        long maximumRows,
        String caveat
    ) { }
}
