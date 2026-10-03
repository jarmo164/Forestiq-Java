package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OwnerParcelRelationLegacyListRetirementTest {

    @Test
    void legacyRelationListReturnsGoneWithoutExecutingSearch() {
        OwnerParcelRelationService service = mock(OwnerParcelRelationService.class);
        OwnerParcelRelationController controller = new OwnerParcelRelationController(service);

        assertThatThrownBy(() -> controller.search("", null, "", 100))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(exception.getReason()).contains("/api/v1/ownership-relations/cursor");
            });

        verifyNoInteractions(service);
    }
}
