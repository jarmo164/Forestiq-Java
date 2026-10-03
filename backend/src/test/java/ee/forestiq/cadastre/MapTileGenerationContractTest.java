package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class MapTileGenerationContractTest {
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GENERATION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock GenerationMapTileRepository repository;
    @Mock OrganizationContext organizationContext;
    @Mock OwnerAuthorizationService authorizationService;

    @Test
    void currentGenerationKeyServesTheMatchingPublishedGeneration() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION);
        when(repository.landParcelsTile(ORG, Optional.empty(), GENERATION, 12, 2310, 1210)).thenReturn(new byte[] {7});
        MapTileService service = service();

        byte[] tile = service.landParcelsForGeneration(12, 2310, 1210, GENERATION + ":r3");

        assertThat(tile).containsExactly(7);
        verify(repository).landParcelsTile(ORG, Optional.empty(), GENERATION, 12, 2310, 1210);
    }

    @Test
    void staleGenerationKeyFailsBeforeTileGeneration() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION);
        MapTileService service = service();

        assertThatThrownBy(() -> service.landParcelsForGeneration(
            12, 2310, 1210, "33333333-3333-3333-3333-333333333333:r3"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(exception.getReason()).contains("no longer current");
            });

        verify(repository, never()).landParcelsTile(ORG, Optional.empty(), GENERATION, 12, 2310, 1210);
    }

    private MapTileService service() {
        return new MapTileService(repository, organizationContext, authorizationService, new SimpleMeterRegistry());
    }
}
