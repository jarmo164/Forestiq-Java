package ee.forestiq.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MapTileAdmissionGateTest {

    @Test
    void distinctTileBuildsAreBoundedAndRejectedWorkNeverRuns() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MapTileAdmissionGate admission = new MapTileAdmissionGate(meters, 1, 2_000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = Thread.ofVirtual().start(() -> admission.execute(
            MapTileAdmissionGate.Family.CADASTRE,
            () -> {
                entered.countDown();
                try { release.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return new byte[] {1};
            }));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        final boolean[] rejectedWorkRan = {false};
        assertThatThrownBy(() -> admission.execute(
            MapTileAdmissionGate.Family.CADASTRE,
            () -> {
                rejectedWorkRan[0] = true;
                return new byte[] {2};
            }))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("429");

        assertThat(rejectedWorkRan[0]).isFalse();
        assertThat(meters.counter("forestiq.map.tile.overload", "family", "cadastre").count()).isEqualTo(1.0);
        release.countDown();
        first.join();
        assertThat(admission.availablePermits(MapTileAdmissionGate.Family.CADASTRE)).isEqualTo(1);
        admission.close();
    }

    @Test
    void deadlineReturnsBoundedFailureAndDoesNotPublishAResult() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MapTileAdmissionGate admission = new MapTileAdmissionGate(meters, 1, 20);

        assertThatThrownBy(() -> admission.execute(
            MapTileAdmissionGate.Family.OVERLAY,
            () -> {
                try { Thread.sleep(5_000); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", ex);
                }
                return new byte[] {9};
            }))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503");

        assertThat(meters.counter("forestiq.map.tile.deadline", "family", "overlay").count()).isEqualTo(1.0);
        admission.close();
    }
}
