package ee.forestiq.owner.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.forestiq.owner.Owner;
import ee.forestiq.owner.OwnerActivityRepository;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.owner.OwnerRepository;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.ManagementAssignment;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.WorkflowContactActivity;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class OwnerCollaborationServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private OwnerRepository ownerRepository;
    @Mock private OwnerActivityRepository activityRepository;
    @Mock private OwnerAuthorizationService authorization;
    @Mock private OwnerService ownerService;
    @Mock private OrganizationContext organizationContext;

    private OwnerCollaborationService service;

    @BeforeEach
    void setUp() {
        service = new OwnerCollaborationService(
            ownerRepository, activityRepository, authorization, ownerService, organizationContext);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
    }

    @Test
    void dealLifecycleMapsBusinessIntentToOwnerOwnedStatusTransition() {
        Owner owner = owner(OWNER_ID, OwnerStatus.EVALUATION, "actor-subject");
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));

        service.applyDealLifecycle(OWNER_ID, DealLifecycleTransition.COMPLETE_EVALUATION_VALUED);

        verify(authorization).assertCanMutate(owner);
        verify(ownerService).changeStatus(OWNER_ID,
            new ChangeStatusRequest(OwnerStatus.VALUED,
                "Hindamisotsus valmis; klient vajab pakkumisega järelkontakti.", null));
    }

    @Test
    void workflowContactPersistsStructuredTimelineThroughOwnerAdapter() {
        Owner owner = owner(OWNER_ID, OwnerStatus.INTERESTED, "actor-subject");
        UUID persistedId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(organizationContext.subject()).thenReturn("actor-subject");
        when(ownerRepository.findByIdAndOrganizationId(OWNER_ID, ORGANIZATION_ID)).thenReturn(Optional.of(owner));
        when(activityRepository.addWorkflowContact(
            ORGANIZATION_ID, OWNER_ID, "PHONE", "Kontakt loodud", "actor-subject",
            "PHONE", "CONTACTED", null, null, null)).thenReturn(persistedId);

        UUID result = service.recordWorkflowContact(OWNER_ID,
            new WorkflowContactActivity("PHONE", "Kontakt loodud", "PHONE", "CONTACTED", null, null, null));

        assertThat(result).isEqualTo(persistedId);
        verify(authorization).assertCanMutate(owner);
    }

    @Test
    void managementAssignmentLocksThenUpdatesOnlyChangedOwnersAndWritesAudit() {
        Owner unassigned = owner(OWNER_ID, OwnerStatus.INTERESTED, null);
        Owner unchanged = owner(OTHER_OWNER_ID, OwnerStatus.INTERESTED, "caller-b");
        when(organizationContext.subject()).thenReturn("actor-subject");
        when(ownerRepository.findByIdInAndOrganizationIdForUpdate(
            org.mockito.ArgumentMatchers.anyCollection(), eq(ORGANIZATION_ID))).thenReturn(List.of(unassigned, unchanged));
        when(ownerRepository.save(unassigned)).thenReturn(unassigned);

        var outcomes = service.applyManagementAssignments(List.of(
            new ManagementAssignment(OTHER_OWNER_ID, "caller-b"),
            new ManagementAssignment(OWNER_ID, "caller-a")), "Juhi kinnitatud põhjendus");

        assertThat(outcomes).extracting(OwnerCollaborationPort.ManagementAssignmentOutcome::changed)
            .containsExactly(false, true);
        assertThat(unassigned.getAssignedToSubject()).isEqualTo("caller-a");
        verify(authorization).assertCanChangeAssignment();
        verify(ownerRepository).save(unassigned);
        verify(activityRepository).addActivity(eq(ORGANIZATION_ID), eq(OWNER_ID), eq("ASSIGNED"),
            eq("Töö määrati kasutajale caller-a. Juhi kinnitatud põhjendus"), eq("actor-subject"));
    }

    private static Owner owner(UUID id, String status, String assignedTo) {
        return new Owner(id, ORGANIZATION_ID, "Mari Mets", "48901010011", "mari@example.test",
            "+37255500101", status, assignedTo);
    }
}
