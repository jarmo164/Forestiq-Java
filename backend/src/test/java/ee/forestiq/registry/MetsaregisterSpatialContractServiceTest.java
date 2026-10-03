package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetsaregisterSpatialContractServiceTest {
    private final MetsaregisterLayerManifestService manifest =
        new MetsaregisterLayerManifestService(new ObjectMapper());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MetsaregisterSpatialContractService contract =
        new MetsaregisterSpatialContractService(manifest, meters);
    private final MetsaregisterLayerManifestService.Layer layer =
        manifest.requireLayer("metsaregister:katsealad");

    @Test
    void sourceCanonicalAndPresentationCrsAreExplicit() {
        var wms = contract.contractForInterface("WMS");
        var wfs = contract.contractForInterface("WFS");

        assertThat(wms.sourceCrs()).isEqualTo("EPSG:3301");
        assertThat(wms.axisOrder()).isEqualTo("EAST_NORTH");
        assertThat(wfs.sourceCrs()).isEqualTo("EPSG:3301");
        assertThat(MetsaregisterSpatialContractService.CANONICAL_STORAGE_CRS).isEqualTo("EPSG:3301");
        assertThat(MetsaregisterSpatialContractService.PRESENTATION_CRS).isEqualTo("EPSG:3857");
    }

    @Test
    void acceptsKnownValidEstoniaBboxesInCanonicalAndPresentationCrs() {
        var canonical = contract.normalizeMapRequest(
            layer, "EPSG:3301", "500000,6450000,510000,6460000");
        var presentation = contract.normalizeMapRequest(
            layer, "EPSG:3857", "2700000,8000000,2710000,8010000");

        assertThat(canonical.crs()).isEqualTo("EPSG:3301");
        assertThat(canonical.bbox()).isEqualTo("500000.000,6450000.000,510000.000,6460000.000");
        assertThat(presentation.crs()).isEqualTo("EPSG:3857");
    }

    @Test
    void rejectsSwappedAxesWrongMagnitudeAndUnexpectedCrs() {
        assertThatThrownBy(() -> contract.normalizeMapRequest(
            layer, "EPSG:3301", "6450000,500000,6460000,510000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("axis-order/CRS/double-transform");

        assertThatThrownBy(() -> contract.normalizeMapRequest(
            layer, "EPSG:3301", "24.7,59.4,25.0,60.0"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> contract.normalizeMapRequest(
            layer, "EPSG:3301", "2700000,8000000,2710000,8010000"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> contract.normalizeMapRequest(
            layer, "EPSG:4326", "24,57,28,60"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported map CRS");
    }

    @Test
    void spatialDriftIsVisibleAsLowCardinalityMetric() {
        assertThatThrownBy(() -> contract.normalizeMapRequest(
            layer, "EPSG:3301", "24.7,59.4,25.0,60.0"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(meters.get("forestiq.metsaregister.spatial_contract_rejections")
            .tag("layer", "metsaregister:katsealad")
            .tag("reason", "implausible_bbox")
            .counter().count()).isEqualTo(1.0);
    }
}
