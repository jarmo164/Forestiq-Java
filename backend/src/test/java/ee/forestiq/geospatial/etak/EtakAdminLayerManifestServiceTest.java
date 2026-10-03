package ee.forestiq.geospatial.etak;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EtakAdminLayerManifestServiceTest {

    @Test
    void checkedInManifestKeepsApprovedBusinessScopeExplicit() {
        EtakAdminLayerManifestService service = new EtakAdminLayerManifestService(new ObjectMapper());

        assertThat(service.manifest().canonicalSrid()).isEqualTo(3301);
        assertThat(service.enabledLayers()).extracting(EtakAdminLayerManifestService.Layer::sourceKey)
            .contains(
                "MARU_ETAK_WOODY",
                "MARU_ETAK_WETLAND",
                "MARU_ETAK_STANDING_WATER",
                "MARU_ETAK_FLOWING_WATER",
                "MARU_ETAK_ROAD",
                "MARU_ETAK_ARABLE",
                "MARU_ETAK_OPEN",
                "MARU_ADMIN_COUNTY",
                "MARU_ADMIN_MUNICIPALITY",
                "MARU_ADMIN_SETTLEMENT")
            .doesNotContain("MARU_ETAK_BUILDING");

        var woody = service.require("MARU_ETAK_WOODY");
        assertThat(woody.providerLayer()).isEqualTo("etak:E_305_puittaimestik_a");
        assertThat(woody.identityField()).isEqualTo("etak_id");
        assertThat(woody.caveat()).contains("topograafiline maakate").contains("Metsaregistri");
        assertThat(woody.importMode()).isEqualTo("WFS_PAGED");
        assertThat(woody.sourceSrid()).isEqualTo(3301);

        var building = service.require("MARU_ETAK_BUILDING");
        assertThat(building.enabled()).isFalse();
        assertThat(building.disabledReason()).contains("Puudub ForestIQ");
    }
}
