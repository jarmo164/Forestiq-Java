package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.security.OrganizationContext;

class OwnerLegacyListRetirementTest {

    @Test
    void legacyOffsetListReturnsGoneWithoutExecutingOwnerSearch() {
        OwnerService ownerService = mock(OwnerService.class);
        OwnerController controller = new OwnerController(
            ownerService,
            mock(OrganizationIdentityDirectory.class),
            mock(OrganizationContext.class));

        assertThatThrownBy(() -> controller.search("", 0, 50))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GONE);
                assertThat(exception.getReason()).contains("/api/v1/owners/cursor");
            });

        verifyNoInteractions(ownerService);
    }
}
