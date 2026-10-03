package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerDtos.AssignWorkRequest;
import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.owner.OwnerDtos.OwnerDetails;
import ee.forestiq.owner.OwnerDtos.SaveOwnerStatusRequest;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class OwnerServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private OwnerRepository ownerRepository;
    @Mock private OwnerQueryRepository queryRepository;
    @Mock private OwnerParcelRepository parcelRepository;
    @Mock private OwnerActivityRepository activityRepository;
    @Mock private OwnerFollowingRepository followingRepository;
    @Mock private OwnerStatusDefinitionRepository statusDefinitionRepository;
    @Mock private OwnerAssignmentRepository assignmentRepository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService ownerAuthorizationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private OwnerService service;

    @BeforeEach
    void setUp() {
        service = new OwnerService(ownerRepository, queryRepository, parcelRepository, activityRepository, followingRepository,
            statusDefinitionRepository, assignmentRepository, organizationContext, ownerAuthorizationService, eventPublisher);
    }

    @Test
    void searchRestrictsOwnersToAssignedCallerWhenPolicyRequiresIt() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(ownerAuthorizationService.assignedSubjectRestriction()).thenReturn(Optional.of("keycloak-user-subject"));
        when(queryRepository.countOwners(ORGANIZATION_ID, "Mari", "keycloak-user-subject")).thenReturn(1L);
        when(queryRepository.findOwners(ORGANIZATION_ID, "Mari", 0, 20, "keycloak-user-subject")).thenReturn(List.of());

        service.search("Mari", 0, 20);

        verify(queryRepository).countOwners(ORGANIZATION_ID, "Mari", "keycloak-user-subject");
        verify(queryRepository).findOwners(ORGANIZATION_ID, "Mari", 0, 20, "keycloak-user-subject");
    }

    @Test
    void changeStatusOnlyWithinAuthenticatedOrganizationAndEmitsEvent() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(organizationContext.subject()).thenReturn("keycloak-user-subject");
        when(statusDefinitionRepository.statusDefinitionExists(ORGANIZATION_ID, OwnerStatus.CONTACTED)).thenReturn(true);

        Owner owner = new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.NEW, "keycloak-user-subject");
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));
        when(ownerRepository.save(any())).thenReturn(owner);
        when(ownerAuthorizationService.canReadSensitiveOwnerFields()).thenReturn(true);
        when(parcelRepository.parcelCount(ORGANIZATION_ID, OWNER_ID)).thenReturn(0L);
        when(parcelRepository.findParcels(ORGANIZATION_ID, OWNER_ID)).thenReturn(List.of());
        when(activityRepository.findActivities(ORGANIZATION_ID, OWNER_ID)).thenReturn(List.of());
        when(followingRepository.findFollowings(OWNER_ID)).thenReturn(List.of());

        OwnerDetails result = service.changeStatus(OWNER_ID, new ChangeStatusRequest("CONTACTED", null, null));

        assertThat(result.status()).isEqualTo("CONTACTED");
        verify(ownerAuthorizationService).assertCanMutate(owner);
        verify(ownerRepository).save(owner);
        verify(activityRepository).addActivity(eq(ORGANIZATION_ID), eq(OWNER_ID), eq("STATUS_CHANGED"), any(String.class), eq("keycloak-user-subject"));
        verify(activityRepository).addUserStatusChangeRecord(ORGANIZATION_ID, "keycloak-user-subject", OwnerStatus.NEW, OwnerStatus.CONTACTED);
        verify(eventPublisher).publishEvent(new OwnerService.OwnerStatusChangedEvent(ORGANIZATION_ID, OWNER_ID, "CONTACTED"));
    }

    @Test
    void changeStatus_rejectsCallerAssignmentChangeBeforeSaving() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(statusDefinitionRepository.statusDefinitionExists(ORGANIZATION_ID, OwnerStatus.CONTACTED)).thenReturn(true);

        Owner owner = new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.NEW, "keycloak-user-subject");
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));
        org.springframework.security.access.AccessDeniedException denial =
            new org.springframework.security.access.AccessDeniedException("Only organization admins and CRM managers can change owner assignment");
        org.mockito.Mockito.doThrow(denial).when(ownerAuthorizationService).assertCanChangeAssignment();

        assertThatThrownBy(() -> service.changeStatus(OWNER_ID, new ChangeStatusRequest("CONTACTED", null, "other-caller")))
            .isSameAs(denial);

        verify(ownerAuthorizationService).assertCanMutate(owner);
        verify(ownerAuthorizationService).assertCanChangeAssignment();
        verify(ownerRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void changeStatus_rejectsMovingDeceasedOwnerBackToSalesPipeline() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(statusDefinitionRepository.statusDefinitionExists(ORGANIZATION_ID, OwnerStatus.CONTACTED)).thenReturn(true);
        Owner deceasedOwner = new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.DECEASED, null);
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(deceasedOwner));

        assertThatThrownBy(() -> service.changeStatus(OWNER_ID, new ChangeStatusRequest(OwnerStatus.CONTACTED, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Surnud omaniku staatust");

        verify(ownerRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void update_rejectsStaleOwnerVersionBeforeSaving() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        Owner owner = new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.NEW, null);
        ReflectionTestUtils.setField(owner, "version", 7L);
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.update(OWNER_ID, new OwnerDtos.UpdateOwnerRequest("Mari Mänd", null, null, null, null, null, 6L)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Andmed muutusid");

        verify(ownerRepository, never()).save(any());
    }

    @Test
    void changeStatus_throwsWhenOwnerNotInOrganization() {
        UUID otherOrg = UUID.randomUUID();
        when(organizationContext.requiredOrganizationId()).thenReturn(otherOrg);
        when(statusDefinitionRepository.statusDefinitionExists(otherOrg, OwnerStatus.CONTACTED)).thenReturn(true);
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, otherOrg)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(OWNER_ID, new ChangeStatusRequest("CONTACTED", null, null)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void changeStatus_rejectsStatusMissingFromAuthenticatedOrganization() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(statusDefinitionRepository.statusDefinitionExists(ORGANIZATION_ID, "PRIVATE_STATUS")).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(OWNER_ID, new ChangeStatusRequest("PRIVATE_STATUS", null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Unknown owner status");

        verify(statusDefinitionRepository).statusDefinitionExists(ORGANIZATION_ID, "PRIVATE_STATUS");
        verifyNoInteractions(ownerRepository, eventPublisher);
    }

    @Test
    void removeFollowing_resolvesOwnerInsideAuthenticatedOrganizationBeforeDeleting() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeFollowing(OWNER_ID))
            .isInstanceOf(ResponseStatusException.class);

        verify(followingRepository, never()).removeFollowing(any(UUID.class), any(String.class));
    }

    @Test
    void assignWork_rejectsRequestContainingOwnerFromAnotherOrganization() {
        UUID outsideOwnerId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(assignmentRepository.findExistingOwnerIds(ORGANIZATION_ID, List.of(OWNER_ID, outsideOwnerId)))
            .thenReturn(List.of(OWNER_ID));

        assertThatThrownBy(() -> service.assignWork(new AssignWorkRequest("caller-1", List.of(OWNER_ID, outsideOwnerId), false)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("outside the organization");

        verify(assignmentRepository).findExistingOwnerIds(ORGANIZATION_ID, List.of(OWNER_ID, outsideOwnerId));
        verify(assignmentRepository, never()).setCallerForOwners(any(UUID.class), any(String.class), any());
        verify(assignmentRepository, never()).setStatusForOwners(any(UUID.class), any(String.class), any());
        verify(activityRepository, never()).addActivity(any(UUID.class), any(UUID.class), any(String.class), any(String.class), any(String.class));
        verify(followingRepository, never()).addFollowing(any(UUID.class), any(String.class));
    }

    @Test
    void assignWork_updatesOnlyValidatedOwnersInAuthenticatedOrganization() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(organizationContext.subject()).thenReturn("admin-subject");
        when(assignmentRepository.findExistingOwnerIds(ORGANIZATION_ID, List.of(OWNER_ID))).thenReturn(List.of(OWNER_ID));

        service.assignWork(new AssignWorkRequest("caller-1", List.of(OWNER_ID), false));

        verify(assignmentRepository).setCallerForOwners(ORGANIZATION_ID, "caller-1", List.of(OWNER_ID));
        verify(assignmentRepository).setStatusForOwners(ORGANIZATION_ID, OwnerStatus.ASSIGNED, List.of(OWNER_ID));
        verify(activityRepository).addActivity(eq(ORGANIZATION_ID), eq(OWNER_ID), eq("ASSIGNED"), any(String.class), eq("admin-subject"));
        verify(followingRepository).addFollowing(OWNER_ID, "caller-1");
    }

    @Test
    void statusDefinitionWritesUseAuthenticatedOrganization() {
        SaveOwnerStatusRequest request = new SaveOwnerStatusRequest("CUSTOM", "123abc", 7);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);

        service.saveStatusDefinition(request);
        service.deleteStatusDefinition("CUSTOM");

        verify(statusDefinitionRepository).upsertStatusDefinition(ORGANIZATION_ID, request);
        verify(statusDefinitionRepository).deleteStatusDefinition(ORGANIZATION_ID, "CUSTOM");
        verifyNoMoreInteractions(statusDefinitionRepository);
    }

    @Test
    void getRedactsSensitiveFieldsAndHistoryWhenPolicyDeniesAccess() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        Owner owner = new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.NEW, "manager-subject");
        owner.setAddress("Sensitive address");
        owner.setInfo("Sensitive note");
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));
        when(parcelRepository.parcelCount(ORGANIZATION_ID, OWNER_ID)).thenReturn(2L);
        when(parcelRepository.findParcels(ORGANIZATION_ID, OWNER_ID)).thenReturn(List.of());
        when(ownerAuthorizationService.canReadSensitiveOwnerFields()).thenReturn(false);

        OwnerDetails result = service.get(OWNER_ID);

        assertThat(result.displayName()).isEqualTo("Mari Mets");
        assertThat(result.parcelCount()).isEqualTo(2L);
        assertThat(result.personalOrRegistryCode()).isNull();
        assertThat(result.email()).isNull();
        assertThat(result.phone()).isNull();
        assertThat(result.address()).isNull();
        assertThat(result.info()).isNull();
        assertThat(result.activities()).isEmpty();
        assertThat(result.followings()).isEmpty();
        verifyNoInteractions(activityRepository, followingRepository);
    }

}
