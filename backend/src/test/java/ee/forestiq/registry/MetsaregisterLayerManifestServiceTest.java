package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MetsaregisterLayerManifestServiceTest {
    private final MetsaregisterLayerManifestService manifest =
        new MetsaregisterLayerManifestService(new ObjectMapper());

    @Test
    void declaresEveryKnownCatalogueLayerExactlyOnce() {
        Set<String> expected = Set.of(
            "metsaregister:isearasused", "metsaregister:kahjustused", "metsaregister:katsealad",
            "metsaregister:kl_kasvukoht", "metsaregister:kl_omandivorm", "metsaregister:kl_rinne",
            "metsaregister:kl_puuliik", "metsaregister:eraldis", "metsaregister:eraldis_element",
            "metsaregister:mr__teema_raie_diameeter", "metsaregister:mr__teema_raie_liik",
            "metsaregister:mr__teema_raie_taius", "metsaregister:mr__teema_raie_vanus",
            "metsaregister:natura_2000_alad", "metsaregister:yp_kaitsemets", "metsaregister:mke_arhiiv",
            "metsaregister:mue", "metsaregister:kuusekooreyrask_mke", "metsaregister:mke",
            "metsaregister:teatis_arhiiv", "metsaregister:teatis",
            "metsaregister:pesitsusrahu_maatriks", "metsaregister:raadatud_metsateatised");

        assertThat(manifest.layers()).extracting(MetsaregisterLayerManifestService.Layer::key)
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void onlyExplicitDomainLayersAuthorizeLocalIngestion() {
        assertThat(manifest.isLocalDomainLayer("metsaregister:teatis")).isTrue();
        assertThat(manifest.isLocalDomainLayer("metsaregister:eraldis")).isTrue();
        assertThat(manifest.isLocalDomainLayer("metsaregister:katsealad")).isFalse();
        assertThat(manifest.isLocalDomainLayer("metsaregister:pesitsusrahu_maatriks")).isFalse();
        assertThat(manifest.isLocalDomainLayer("metsaregister:raadatud_metsateatised")).isFalse();

        assertThat(manifest.approvedLocalLayerNames()).containsExactlyInAnyOrder(
            "metsaregister:teatis", "metsaregister:teatis_arhiiv",
            "metsaregister:eraldis", "metsaregister:eraldis_element",
            "metsaregister:isearasused", "metsaregister:kahjustused",
            "metsaregister:kl_kasvukoht", "metsaregister:kl_omandivorm",
            "metsaregister:kl_rinne", "metsaregister:kl_puuliik");
    }

    @Test
    void mapRegistryContainsOnlyEnabledProviderMapPresentations() {
        assertThat(manifest.mapLayers()).isNotEmpty()
            .allSatisfy(layer -> {
                assertThat(layer.enabled()).isTrue();
                assertThat(layer.mapPresentationMode())
                    .isIn(MetsaregisterLayerManifestService.MapPresentationMode.WMS,
                        MetsaregisterLayerManifestService.MapPresentationMode.WMTS);
            });
        assertThat(manifest.mapLayers()).extracting(MetsaregisterLayerManifestService.Layer::key)
            .doesNotContain("metsaregister:eraldis_element", "metsaregister:raadatud_metsateatised");
    }
}
