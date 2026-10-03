package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.forestiq.cadastre.ParcelMapSummary.ParcelOwnerSummary;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class MapTileControllerAuthorizationTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GENERATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private GenerationMapTileRepository repository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService authorizationService;

    @Test
    void callerRestrictionIsAppliedToVectorTileQuery() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.of("caller-subject"));
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_ID);
        when(repository.landParcelsTile(ORG_ID, Optional.of("caller-subject"), GENERATION_ID, 0, 0, 0))
            .thenReturn(new byte[] {1});

        MapTileController controller = new MapTileController(service());

        assertThat(controller.landParcels(0, 0, 0).getBody()).containsExactly((byte) 1);
        verify(repository).landParcelsTile(ORG_ID, Optional.of("caller-subject"), GENERATION_ID, 0, 0, 0);
    }

    @Test
    void generationEndpointReturnsServerOwnedPublishedGeneration() {
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_ID);
        MapTileController controller = new MapTileController(service());

        var response = controller.generation();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().generationId()).isEqualTo(GENERATION_ID);
        assertThat(response.getBody().available()).isTrue();
        assertThat(response.getBody().representationVersion()).isEqualTo(MapTileService.REPRESENTATION_VERSION);
    }

    @Test
    void callerRestrictionIsAppliedBeforeParcelSummaryIsReturned() {
        var owners = List.of(new ParcelOwnerSummary(OWNER_ID, "Mari Mets", "ASSIGNED"));
        var summary = new ParcelMapSummary(
            "12345:001:0001",
            OWNER_ID,
            "Mari Mets",
            "ASSIGNED",
            "caller-subject",
            "+37255500101",
            "Test kinnistu",
            "Test tee 1",
            "12345",
            "REG-1",
            "MAATULUNDUSMAA",
            null,
            BigDecimal.TEN,
            null,
            BigDecimal.ONE,
            null,
            null,
            null,
            null,
            null,
            "Tartumaa",
            "Tartu",
            false,
            26.72,
            58.38,
            26.70,
            58.37,
            26.74,
            58.39,
            owners,
            GENERATION_ID,
            null,
            1,
            0,
            0,
            0,
            0);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.of("caller-subject"));
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_ID);
        when(repository.landParcelSummary(ORG_ID, Optional.of("caller-subject"), GENERATION_ID, "12345:001:0001"))
            .thenReturn(Optional.of(summary));

        MapTileController controller = new MapTileController(service());

        assertThat(controller.landParcelSummary("12345:001:0001")).isEqualTo(summary);
        assertThat(summary.owners()).containsExactlyElementsOf(owners);
        assertThat(summary.centroidLongitude()).isEqualTo(26.72);
        verify(repository).landParcelSummary(ORG_ID, Optional.of("caller-subject"), GENERATION_ID, "12345:001:0001");
    }

    private MapTileService service() {
        return new MapTileService(repository, organizationContext, authorizationService, new SimpleMeterRegistry());
    }
}
