package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class CadastreControllerAuthorizationTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CADASTRAL_CODE = "12345:001:0001";

    @Mock private CadastreRepository repository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService authorizationService;

    @Test
    void cadastreReadChecksAllActiveOwnerAssignmentsBeforeReturningData() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        List<String> assignments = List.of("other-subject", "caller-subject");
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));
        org.mockito.Mockito.doThrow(new AccessDeniedException("Parcel has no active owner assigned to the current caller"))
            .when(authorizationService).assertCanReadAnyAssignedSubject(assignments);

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);

        assertThatThrownBy(() -> controller.getLabels(CADASTRAL_CODE))
            .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).findLabels(ORG_ID, CADASTRAL_CODE);
    }

    @Test
    void cadastreMutationChecksAllActiveOwnerAssignmentsBeforeWriting() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        List<String> assignments = List.of("other-subject");
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));
        org.mockito.Mockito.doThrow(new AccessDeniedException("Parcel has no active owner assigned to the current caller"))
            .when(authorizationService).assertCanMutateAnyAssignedSubject(assignments);

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);

        assertThatThrownBy(() -> controller.addLabel(CADASTRAL_CODE, "DRAINAGE"))
            .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).addLabel(ORG_ID, CADASTRAL_CODE, "DRAINAGE");
    }

    @Test
    void notificationListAppliesHardLimitInsideAuthorizedTenantScope() {
        List<String> assignments = List.of("caller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);
        controller.getNotifications(CADASTRAL_CODE, 10_000);

        verify(authorizationService).assertCanReadAnyAssignedSubject(assignments);
        verify(repository).findNotifications(ORG_ID, CADASTRAL_CODE, 100);
    }

    @Test
    void notificationPageForwardsCursorAndEnforcesMinimumLimitInsideTenantScope() {
        List<String> assignments = List.of("caller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);
        controller.getNotificationPage(CADASTRAL_CODE, 321L, 0);

        verify(authorizationService).assertCanReadAnyAssignedSubject(assignments);
        verify(repository).findNotificationPage(ORG_ID, CADASTRAL_CODE, 321L, 1);
    }

    @Test
    void forestFeaturePageForwardsCursorAndCapsLargeLimitInsideTenantScope() {
        List<String> assignments = List.of("caller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);
        controller.getForestFeaturePage(CADASTRAL_CODE, 987L, 10_000);

        verify(authorizationService).assertCanReadAnyAssignedSubject(assignments);
        verify(repository).findForestFeaturePage(ORG_ID, CADASTRAL_CODE, 987L, 100);
    }

    @Test
    void subPartPageForwardsCursorAndCapsLargeLimitInsideTenantScope() {
        List<String> assignments = List.of("caller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);
        controller.getSubPartPage(CADASTRAL_CODE, 654L, 10_000);

        verify(authorizationService).assertCanReadAnyAssignedSubject(assignments);
        verify(repository).findSubPartPage(ORG_ID, CADASTRAL_CODE, 654L, 100);
    }


    @Test
    void subPartGeometryByCodeAuthorizesBeforeReadingTenantScopedGeometry() {
        List<String> assignments = List.of("caller-subject");
        var geometry = new CadastreDtos.SubPartGeometryDto(77L, CADASTRAL_CODE, 12, "POLYGON((24 59,25 59,25 60,24 59))");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));
        when(repository.findSubPartGeometryByCode(ORG_ID, CADASTRAL_CODE, 12))
            .thenReturn(java.util.Optional.of(geometry));

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);

        assertThat(controller.getSubPartGeometryByCode(CADASTRAL_CODE, 12)).isEqualTo(geometry);
        verify(authorizationService).assertCanReadAnyAssignedSubject(assignments);
        verify(repository).findSubPartGeometryByCode(ORG_ID, CADASTRAL_CODE, 12);
    }

    @Test
    void rawForestFeatureIsNotQueriedWhenTenantAssignmentCheckFails() {
        List<String> assignments = List.of("other-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(repository.findAssignmentScope(ORG_ID, CADASTRAL_CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, assignments));
        org.mockito.Mockito.doThrow(new AccessDeniedException("Parcel has no active owner assigned to the current caller"))
            .when(authorizationService).assertCanReadAnyAssignedSubject(assignments);

        CadastreController controller = new CadastreController(repository, organizationContext, authorizationService);

        assertThatThrownBy(() -> controller.getForestFeatureRaw(CADASTRAL_CODE, 99L))
            .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).findForestFeatureRaw(ORG_ID, CADASTRAL_CODE, 99L);
    }
}
