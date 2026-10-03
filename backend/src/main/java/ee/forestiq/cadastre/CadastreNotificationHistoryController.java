package ee.forestiq.cadastre;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationArchivePage;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationPage;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/cadastres")
@Tag(name = "Cadastre notification history", description = "Bounded active and archived forest-notification reads")
public class CadastreNotificationHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CadastreNotificationHistoryRepository notificationRepository;
    private final CadastreRepository cadastreRepository;
    private final OrganizationContext organizationContext;
    private final OwnerAuthorizationService authorizationService;

    public CadastreNotificationHistoryController(
        CadastreNotificationHistoryRepository notificationRepository,
        CadastreRepository cadastreRepository,
        OrganizationContext organizationContext,
        OwnerAuthorizationService authorizationService
    ) {
        this.notificationRepository = notificationRepository;
        this.cadastreRepository = cadastreRepository;
        this.organizationContext = organizationContext;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/{cadastralCode}/notifications/current/page")
    @Operation(summary = "Get current non-archived notifications with a stable keyset cursor")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public CadastreNotificationPage currentPage(
        @PathVariable String cadastralCode,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        UUID orgId = organizationContext.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return notificationRepository.findCurrentPage(orgId, cadastralCode, cursor, boundedLimit(limit));
    }

    @GetMapping("/{cadastralCode}/notifications/archive/page")
    @Operation(summary = "Get archived notifications inside an explicit provider archive-date range")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public CadastreNotificationArchivePage archivePage(
        @PathVariable String cadastralCode,
        @RequestParam long from,
        @RequestParam long to,
        @RequestParam(required = false) Long cursorArchiveDate,
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "50") int limit
    ) {
        if (from > to) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive range 'from' must be <= 'to'");
        }
        if ((cursorArchiveDate == null) != (cursorId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive cursor requires both cursorArchiveDate and cursorId");
        }

        UUID orgId = organizationContext.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return notificationRepository.findArchivePage(
            orgId, cadastralCode, from, to, cursorArchiveDate, cursorId, boundedLimit(limit));
    }

    private int boundedLimit(int requested) {
        if (requested < 1) return 1;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private void assertCanReadCadastre(UUID orgId, String cadastralCode) {
        CadastreRepository.ParcelAssignmentScope scope = cadastreRepository.findAssignmentScope(orgId, cadastralCode);
        if (!scope.parcelExists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre not found");
        }
        authorizationService.assertCanReadAnyAssignedSubject(scope.assignedSubjects());
    }
}
