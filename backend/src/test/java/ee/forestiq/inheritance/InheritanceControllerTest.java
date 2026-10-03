package ee.forestiq.inheritance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class InheritanceControllerTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CASE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID HEIR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private InheritanceService inheritanceService;
    @Mock private OwnerService ownerService;
    @Mock private OrganizationIdentityDirectory identityDirectory;
    @Mock private OrganizationContext organizationContext;
    @Mock private InheritanceNoticeArchiveRepository noticeArchiveRepository;
    @InjectMocks private InheritanceController controller;

    @Test
    void routesWorkspaceSearchAndAllWorkflowMutationsToTheService() {
        var start = new InheritanceService.StartInheritanceCaseRequest("notice-1", "https://notice.example.test", LocalDate.now(), null, null, null, null);
        var status = new InheritanceService.ChangeInheritanceStatusRequest("CONTACTED", "contact made");
        var heir = new InheritanceService.SaveInheritanceHeirRequest("Mari Mets", null, null, null, null, null, null, "NEW", null, "MANUAL");
        var note = new InheritanceService.AddInheritanceNoteRequest("Follow up next week");

        controller.workspace(OWNER_ID);
        controller.cases("mets", "OPEN", "caller", true, 0, 100);
        controller.caseWorkspace(CASE_ID);
        controller.checkOfficialNotices(OWNER_ID);
        controller.start(OWNER_ID, start);
        controller.importOfficialNotice(OWNER_ID, "notice-1");
        controller.changeStatus(CASE_ID, status);
        controller.addHeir(CASE_ID, heir);
        controller.updateHeir(CASE_ID, HEIR_ID, heir);
        controller.addNote(CASE_ID, note);

        verify(ownerService).get(OWNER_ID);
        verify(inheritanceService).workspace(OWNER_ID);
        verify(inheritanceService).searchCases(eq("mets"), eq("OPEN"), eq("caller"), eq(true), any(Integer.class), any(Integer.class));
        verify(inheritanceService).caseWorkspace(CASE_ID);
        verify(inheritanceService).checkOfficialNotices(OWNER_ID);
        verify(inheritanceService).start(OWNER_ID, start);
        verify(inheritanceService).importOfficialNotice(OWNER_ID, "notice-1");
        verify(inheritanceService).changeStatus(CASE_ID, status);
        verify(inheritanceService).addHeir(CASE_ID, heir);
        verify(inheritanceService).updateHeir(CASE_ID, HEIR_ID, heir);
        verify(inheritanceService).addNote(CASE_ID, note);
    }

    @Test
    void listsArchivedNoticesForTheCurrentOrganizationMatchContext() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);

        controller.notices(100);

        verify(noticeArchiveRepository).findRecent(ORGANIZATION_ID, 100);
    }

    @Test
    void validatesNonblankAssigneesAgainstTheCurrentOrganizationButAllowsExplicitUnassignment() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);

        controller.assign(CASE_ID, new InheritanceService.AssignInheritanceCaseRequest("evaluator-subject"));
        controller.assign(CASE_ID, new InheritanceService.AssignInheritanceCaseRequest("  "));

        verify(identityDirectory).requireAssignableUser(eq(ORGANIZATION_ID), eq("evaluator-subject"),
            eq(java.util.Set.of(OrganizationRole.CALLER, OrganizationRole.EVALUATOR,
                OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN)));
        verify(inheritanceService).assignCase(CASE_ID, "evaluator-subject");
        verify(inheritanceService).assignCase(CASE_ID, "  ");
    }
}
