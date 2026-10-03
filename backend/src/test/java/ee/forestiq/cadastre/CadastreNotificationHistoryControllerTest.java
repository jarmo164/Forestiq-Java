package ee.forestiq.cadastre;

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
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class CadastreNotificationHistoryControllerTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CODE = "12345:001:0001";

    @Mock private CadastreNotificationHistoryRepository notificationRepository;
    @Mock private CadastreRepository cadastreRepository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService authorizationService;

    @Test
    void currentPageAuthorizesBeforeReadingAndCapsPageSize() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(cadastreRepository.findAssignmentScope(ORG_ID, CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, List.of("caller")));
        var controller = controller();

        controller.currentPage(CODE, 900L, 10_000);

        verify(authorizationService).assertCanReadAnyAssignedSubject(List.of("caller"));
        verify(notificationRepository).findCurrentPage(ORG_ID, CODE, 900L, 100);
    }

    @Test
    void archivePageRequiresAscendingFiniteRangeBeforeDatabaseRead() {
        var controller = controller();

        assertThatThrownBy(() -> controller.archivePage(CODE, 200, 100, null, null, 50))
            .isInstanceOf(ResponseStatusException.class);

        verify(notificationRepository, never()).findArchivePage(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void archivePageRequiresCompleteTwoColumnCursor() {
        var controller = controller();

        assertThatThrownBy(() -> controller.archivePage(CODE, 100, 200, 150L, null, 50))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void archivePageForwardsBoundedRangeAndStableCursorAfterAuthorization() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(cadastreRepository.findAssignmentScope(ORG_ID, CODE))
            .thenReturn(new CadastreRepository.ParcelAssignmentScope(true, List.of("caller")));
        var controller = controller();

        controller.archivePage(CODE, 100, 200, 150L, 42L, 10_000);

        verify(authorizationService).assertCanReadAnyAssignedSubject(List.of("caller"));
        verify(notificationRepository).findArchivePage(ORG_ID, CODE, 100, 200, 150L, 42L, 100);
    }

    private CadastreNotificationHistoryController controller() {
        return new CadastreNotificationHistoryController(
            notificationRepository, cadastreRepository, organizationContext, authorizationService);
    }
}
