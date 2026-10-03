package ee.forestiq.inheritance;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.inheritance.InheritanceService.AddInheritanceNoteRequest;
import ee.forestiq.inheritance.InheritanceService.ChangeInheritanceStatusRequest;
import ee.forestiq.inheritance.InheritanceService.InheritanceCaseDetails;
import ee.forestiq.inheritance.InheritanceService.OfficialNoticeCheckResult;
import ee.forestiq.inheritance.InheritanceService.InheritanceWorkspace;
import ee.forestiq.inheritance.InheritanceService.SaveInheritanceHeirRequest;
import ee.forestiq.inheritance.InheritanceService.StartInheritanceCaseRequest;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.owner.Pagination;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/inheritance")
public class InheritanceController {

    private static final Set<OrganizationRole> INHERITANCE_ASSIGNMENT_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.EVALUATOR, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);

    private final InheritanceService inheritanceService;
    private final OwnerService ownerService;
    private final OrganizationIdentityDirectory identityDirectory;
    private final OrganizationContext organizationContext;
    private final InheritanceNoticeArchiveRepository noticeArchiveRepository;

    public InheritanceController(InheritanceService inheritanceService, OwnerService ownerService,
                                 OrganizationIdentityDirectory identityDirectory, OrganizationContext organizationContext,
                                 InheritanceNoticeArchiveRepository noticeArchiveRepository) {
        this.inheritanceService = inheritanceService;
        this.ownerService = ownerService;
        this.identityDirectory = identityDirectory;
        this.organizationContext = organizationContext;
        this.noticeArchiveRepository = noticeArchiveRepository;
    }

    @GetMapping("/owners/{ownerId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceWorkspace workspace(@PathVariable UUID ownerId) {
        ownerService.get(ownerId);
        return inheritanceService.workspace(ownerId);
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceService.InheritanceCasePage cases(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String assignedTo,
        @RequestParam(defaultValue = "false") boolean overdue,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pagination.PageRequest paging = Pagination.normalize(page, size);
        return inheritanceService.searchCases(query, status, assignedTo, overdue, paging.page(), paging.size());
    }

    @GetMapping("/notices")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public List<InheritanceNoticeListItem> notices(@RequestParam(defaultValue = "100") int limit) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        return noticeArchiveRepository.findRecent(organizationId, limit).stream()
            .map(InheritanceNoticeListItem::from)
            .toList();
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceService.InheritanceCaseWorkspace caseWorkspace(@PathVariable UUID caseId) {
        return inheritanceService.caseWorkspace(caseId);
    }

    @PatchMapping("/cases/{caseId}/assignment")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public InheritanceService.InheritanceCaseWorkspace assign(
        @PathVariable UUID caseId,
        @RequestBody InheritanceService.AssignInheritanceCaseRequest request
    ) {
        if (request.assignedToSubject() != null && !request.assignedToSubject().isBlank()) {
            identityDirectory.requireAssignableUser(organizationContext.requiredOrganizationId(),
                request.assignedToSubject(), INHERITANCE_ASSIGNMENT_ROLES);
        }
        return inheritanceService.assignCase(caseId, request.assignedToSubject());
    }

    @PostMapping("/owners/{ownerId}/official-notices/check")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public OfficialNoticeCheckResult checkOfficialNotices(@PathVariable UUID ownerId) {
        return inheritanceService.checkOfficialNotices(ownerId);
    }

    @PostMapping("/owners/{ownerId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public InheritanceCaseDetails start(@PathVariable UUID ownerId, @RequestBody StartInheritanceCaseRequest request) {
        return inheritanceService.start(ownerId, request);
    }

    @PostMapping("/owners/{ownerId}/official-notices/{noticeNumber}/import")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public InheritanceCaseDetails importOfficialNotice(@PathVariable UUID ownerId, @PathVariable String noticeNumber) {
        return inheritanceService.importOfficialNotice(ownerId, noticeNumber);
    }

    @PatchMapping("/cases/{caseId}/status")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceCaseDetails changeStatus(@PathVariable UUID caseId, @RequestBody ChangeInheritanceStatusRequest request) {
        return inheritanceService.changeStatus(caseId, request);
    }

    @PostMapping("/cases/{caseId}/heirs")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    public InheritanceCaseDetails addHeir(@PathVariable UUID caseId, @RequestBody SaveInheritanceHeirRequest request) {
        return inheritanceService.addHeir(caseId, request);
    }

    @PutMapping("/cases/{caseId}/heirs/{heirId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceCaseDetails updateHeir(@PathVariable UUID caseId, @PathVariable UUID heirId, @RequestBody SaveInheritanceHeirRequest request) {
        return inheritanceService.updateHeir(caseId, heirId, request);
    }

    @PostMapping("/cases/{caseId}/events")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER')")
    public InheritanceCaseDetails addNote(@PathVariable UUID caseId, @RequestBody AddInheritanceNoteRequest request) {
        return inheritanceService.addNote(caseId, request);
    }

    public record InheritanceNoticeListItem(
        String noticeNumber,
        LocalDate feedDate,
        LocalDate announcementDate,
        String sourceUrl,
        LocalDate deathDate,
        LocalDate certificationDeadline,
        String notaryName,
        String notaryPhone,
        boolean hasPersonalCode,
        boolean ownerMatched,
        boolean imported
    ) {
        static InheritanceNoticeListItem from(InheritanceNoticeArchiveRepository.ArchivedInheritanceNotice notice) {
            return new InheritanceNoticeListItem(
                notice.noticeNumber(), notice.feedDate(), notice.announcementDate(), notice.sourceUrl(),
                notice.deathDate(), notice.certificationDeadline(), notice.notaryName(), notice.notaryPhone(),
                notice.hasPersonalCode(), notice.ownerMatched(), notice.imported());
        }
    }
}
