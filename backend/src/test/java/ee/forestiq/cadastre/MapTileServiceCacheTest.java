package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class MapTileServiceCacheTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GENERATION_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GENERATION_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private GenerationMapTileRepository repository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService authorizationService;

    @Test
    void repeatedTileRequestUsesCachedBytesWithoutSecondRepositoryQuery() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210))
            .thenReturn(new byte[] {1, 2, 3});
        MapTileService service = service();

        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 1, (byte) 2, (byte) 3);

        verify(repository, times(1)).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210);
        assertThat(service.tileCacheEstimatedSize()).isEqualTo(1);
    }

    @Test
    void overviewAndDetailRepresentationsNeverShareAProtectedCacheEntry() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        when(repository.landParcelsOverviewTile(ORG_A, Optional.empty(), GENERATION_A, 10, 575, 302))
            .thenReturn(new byte[] {4});
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 10, 575, 302))
            .thenReturn(new byte[] {9});
        MapTileService service = service();

        assertThat(service.landParcelsOverview(10, 575, 302, 12L)).containsExactly((byte) 4);
        assertThat(service.landParcels(10, 575, 302, 12L)).containsExactly((byte) 9);
        assertThat(service.landParcelsOverview(10, 575, 302, 12L)).containsExactly((byte) 4);
        assertThat(service.landParcels(10, 575, 302, 12L)).containsExactly((byte) 9);

        verify(repository, times(1)).landParcelsOverviewTile(ORG_A, Optional.empty(), GENERATION_A, 10, 575, 302);
        verify(repository, times(1)).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 10, 575, 302);
        assertThat(service.tileCacheEstimatedSize()).isEqualTo(2);
    }

    @Test
    void concurrentColdRequestsShareOneRepositoryGeneration() throws Exception {
        int callers = 8;
        CyclicBarrier startBarrier = new CyclicBarrier(callers);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);

        when(organizationContext.requiredOrganizationId()).thenAnswer(invocation -> {
            startBarrier.await(5, TimeUnit.SECONDS);
            return ORG_A;
        });
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210)).thenAnswer(invocation -> {
            loadStarted.countDown();
            assertThat(releaseLoad.await(5, TimeUnit.SECONDS)).isTrue();
            return new byte[] {4, 5, 6};
        });

        MapTileService service = service();
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> service.landParcels(12, 2310, 1210, 9L)));
            }

            assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            releaseLoad.countDown();

            for (Future<byte[]> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).containsExactly((byte) 4, (byte) 5, (byte) 6);
            }
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }

        verify(repository, times(1)).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210);
        assertThat(service.tileCacheEstimatedSize()).isEqualTo(1);
    }

    @Test
    void clientDataVersionDoesNotChangeSourceOrCacheIdentity() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210))
            .thenReturn(new byte[] {1});
        MapTileService service = service();

        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 1);
        assertThat(service.landParcels(12, 2310, 1210, 8L)).containsExactly((byte) 1);

        verify(repository, times(1)).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210);
    }

    @Test
    void publishedGenerationChangeForcesFreshTileGeneration() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A, GENERATION_B);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210))
            .thenReturn(new byte[] {1});
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_B, 12, 2310, 1210))
            .thenReturn(new byte[] {2});
        MapTileService service = service();

        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 1);
        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 2);

        verify(repository).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210);
        verify(repository).landParcelsTile(ORG_A, Optional.empty(), GENERATION_B, 12, 2310, 1210);
        assertThat(service.tileCacheEstimatedSize()).isEqualTo(2);
    }

    @Test
    void cacheKeyKeepsOrganizationsIsolated() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A, ORG_B);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210))
            .thenReturn(new byte[] {1});
        when(repository.landParcelsTile(ORG_B, Optional.empty(), GENERATION_A, 12, 2310, 1210))
            .thenReturn(new byte[] {2});
        MapTileService service = service();

        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 1);
        assertThat(service.landParcels(12, 2310, 1210, 7L)).containsExactly((byte) 2);

        verify(repository).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 12, 2310, 1210);
        verify(repository).landParcelsTile(ORG_B, Optional.empty(), GENERATION_A, 12, 2310, 1210);
    }

    @Test
    void oversizedDenseTileFailsClosedAndIsNeverCached() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.currentCadastreGenerationId()).thenReturn(GENERATION_A);
        int budget = MapTileService.tileBudgetBytes(13);
        when(repository.landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 13, 4620, 2420))
            .thenReturn(new byte[budget + 1]);
        MapTileService service = service();

        assertThatThrownBy(() -> service.landParcels(13, 4620, 2420, 0L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(ex.getReason()).contains("byte budget");
            });

        verify(repository, times(1)).landParcelsTile(ORG_A, Optional.empty(), GENERATION_A, 13, 4620, 2420);
        assertThat(service.tileCacheEstimatedSize()).isZero();
    }

    @Test
    void zoomBandsHaveExplicitByteBudgets() {
        assertThat(MapTileService.tileBudgetBytes(8)).isEqualTo(256 * 1024);
        assertThat(MapTileService.tileBudgetBytes(10)).isEqualTo(384 * 1024);
        assertThat(MapTileService.tileBudgetBytes(12)).isEqualTo(768 * 1024);
        assertThat(MapTileService.tileBudgetBytes(13)).isEqualTo(1024 * 1024);
    }

    private MapTileService service() {
        return new MapTileService(repository, organizationContext, authorizationService, new SimpleMeterRegistry());
    }
}
