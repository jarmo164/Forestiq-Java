package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaginationTest {

    @Test
    void normalizeAcceptsValuesInsideSharedBounds() {
        Pagination.PageRequest pagination = Pagination.normalize(2, 50);

        assertThat(pagination.page()).isEqualTo(2);
        assertThat(pagination.size()).isEqualTo(50);
        assertThat(pagination.offset()).isEqualTo(100);
    }

    @Test
    void normalizeRejectsNegativePage() {
        assertThatThrownBy(() -> Pagination.normalize(-1, 20))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }

    @Test
    void normalizeRejectsZeroAndOversizedPageSize() {
        assertThatThrownBy(() -> Pagination.normalize(0, 0))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
        assertThatThrownBy(() -> Pagination.normalize(0, Pagination.MAX_SIZE + 1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }

    @Test
    void normalizeRejectsOverflowLikeOffset() {
        assertThatThrownBy(() -> Pagination.normalize(Integer.MAX_VALUE, Pagination.MAX_SIZE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }
}
