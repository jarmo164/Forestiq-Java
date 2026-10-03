package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class OwnerControllerWorkflowBoundaryTest {

    @Mock private OwnerService ownerService;
    @Mock private OrganizationIdentityDirectory identityDirectory;
    @Mock private OrganizationContext organizationContext;

    @Test
    void directEvaluationStatusTransitionIsRejectedBeforeOwnerMutation() {
        OwnerController controller = new OwnerController(ownerService, identityDirectory, organizationContext);
        UUID ownerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThatThrownBy(() -> controller.changeStatus(ownerId,
            new ChangeStatusRequest(OwnerStatus.EVALUATION, "legacy path", null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED")
            .hasMessageContaining("tehingujuhtumi");

        verifyNoInteractions(ownerService, identityDirectory, organizationContext);
    }

    @Test
    void directWaitsForEvaluationStatusTransitionIsRejectedBeforeOwnerMutation() {
        OwnerController controller = new OwnerController(ownerService, identityDirectory, organizationContext);
        UUID ownerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThatThrownBy(() -> controller.changeStatus(ownerId,
            new ChangeStatusRequest(OwnerStatus.WAITS_FOR_EVALUATION, "legacy path", null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED");

        verifyNoInteractions(ownerService, identityDirectory, organizationContext);
    }
}
