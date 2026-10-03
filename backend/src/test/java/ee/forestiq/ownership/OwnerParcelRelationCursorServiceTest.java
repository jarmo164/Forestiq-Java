package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OwnerParcelRelationCursorServiceTest {

    @Test
    void cursorRoundTripPreservesStableKey() {
        Instant updatedAt = Instant.parse("2026-08-28T11:20:30.123456Z");
        UUID ownerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID parcelId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String cursor = OwnerParcelRelationCursorService.encodeCursor(updatedAt, ownerId, parcelId);
        var decoded = OwnerParcelRelationCursorService.decodeCursor(cursor);

        assertThat(decoded.updatedAt()).isEqualTo(updatedAt);
        assertThat(decoded.ownerId()).isEqualTo(ownerId);
        assertThat(decoded.parcelId()).isEqualTo(parcelId);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> OwnerParcelRelationCursorService.decodeCursor("bad-cursor"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Invalid ownership relation cursor");
    }
}
