package ee.forestiq.cadastre;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationDto;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationPage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureDto;
import ee.forestiq.cadastre.CadastreDtos.ForestFeaturePage;
import ee.forestiq.cadastre.CadastreDtos.ForestFeatureRawDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartGeometryDto;
import ee.forestiq.cadastre.CadastreDtos.SubPartPage;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.owner.OwnerDtos.CadastreEvaluation;
import ee.forestiq.owner.OwnerDtos.CadastreLabelsResponse;
import ee.forestiq.owner.OwnerDtos.SaveEvaluationRequest;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/cadastres")
@Tag(name = "Cadastres", description = "Cadastre labels, evaluations, notifications and forest features")
public class CadastreController {

    private static final int MAX_HISTORY_LIMIT = 100;

    private final CadastreRepository cadastreRepository;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorizationService;

    private static final List<String> ALL_LABELS = List.of(
        "CONSERVATION_AREA", "NATURA_2000", "VALUABLE_FOREST", "PROTECTED_SPECIES",
        "DRAINAGE", "ROAD_ACCESS", "BORDER_ZONE", "OTHER");

    public CadastreController(CadastreRepository cadastreRepository, OrganizationContext orgCtx,
                              OwnerAuthorizationService ownerAuthorizationService) {
        this.cadastreRepository = cadastreRepository;
        this.orgCtx = orgCtx;
        this.ownerAuthorizationService = ownerAuthorizationService;
    }

    @GetMapping("/{cadastralCode}/labels")
    @Operation(summary = "Get cadastre labels")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public CadastreLabelsResponse getLabels(@PathVariable String cadastralCode) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        List<String> setLabels = cadastreRepository.findLabels(orgId, cadastralCode);
        List<String> available = ALL_LABELS.stream().filter(l -> !setLabels.contains(l)).toList();
        return new CadastreLabelsResponse(setLabels, available);
    }

    @PostMapping("/{cadastralCode}/labels/{label}")
    @Operation(summary = "Add label to cadastre")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addLabel(@PathVariable String cadastralCode, @PathVariable String label) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanMutateCadastre(orgId, cadastralCode);
        if (!ALL_LABELS.contains(label)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown label");
        cadastreRepository.addLabel(orgId, cadastralCode, label);
    }

    @DeleteMapping("/{cadastralCode}/labels/{label}")
    @Operation(summary = "Remove label from cadastre")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLabel(@PathVariable String cadastralCode, @PathVariable String label) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanMutateCadastre(orgId, cadastralCode);
        cadastreRepository.removeLabel(orgId, cadastralCode, label);
    }

    @GetMapping("/{cadastralCode}/evaluation")
    @Operation(summary = "Get cadastre evaluation")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public CadastreEvaluation getEvaluation(@PathVariable String cadastralCode) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findEvaluation(orgId, cadastralCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre not found"));
    }

    @PutMapping("/{cadastralCode}/evaluation")
    @Operation(summary = "Save cadastre evaluation")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveEvaluation(@PathVariable String cadastralCode, @RequestBody SaveEvaluationRequest req) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanMutateCadastre(orgId, cadastralCode);
        if (!cadastreRepository.saveEvaluation(orgId, cadastralCode, req)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre not found");
    }

    @GetMapping("/{cadastralCode}/notifications")
    @Operation(summary = "Get a bounded list of cadastre notifications")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public List<CadastreNotificationDto> getNotifications(
        @PathVariable String cadastralCode,
        @RequestParam(defaultValue = "20") int limit
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findNotifications(orgId, cadastralCode, boundedHistoryLimit(limit));
    }

    @GetMapping("/{cadastralCode}/notifications/page")
    @Operation(summary = "Get a keyset-paginated page of cadastre notifications")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public CadastreNotificationPage getNotificationPage(
        @PathVariable String cadastralCode,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findNotificationPage(orgId, cadastralCode, cursor, boundedHistoryLimit(limit));
    }

    @GetMapping("/{cadastralCode}/forest-features")
    @Operation(summary = "Get a bounded compatibility list of lightweight forest registry feature summaries")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public List<ForestFeatureDto> getForestFeatures(@PathVariable String cadastralCode) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findForestFeatures(orgId, cadastralCode);
    }

    @GetMapping("/{cadastralCode}/forest-features/page")
    @Operation(summary = "Get a keyset-paginated page of lightweight forest registry feature summaries")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ForestFeaturePage getForestFeaturePage(
        @PathVariable String cadastralCode,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "50") int limit
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findForestFeaturePage(orgId, cadastralCode, cursor, boundedHistoryLimit(limit));
    }

    @GetMapping("/{cadastralCode}/forest-features/{featureId}/raw")
    @Operation(summary = "Get raw attributes and geometry for one forest registry feature")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public ForestFeatureRawDto getForestFeatureRaw(
        @PathVariable String cadastralCode,
        @PathVariable Long featureId
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findForestFeatureRaw(orgId, cadastralCode, featureId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Forest registry feature not found"));
    }

    @GetMapping("/{cadastralCode}/sub-parts")
    @Operation(summary = "Get a bounded compatibility list of lightweight cadastre sub-part summaries")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public List<SubPartDto> getSubParts(@PathVariable String cadastralCode) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findSubParts(orgId, cadastralCode);
    }

    @GetMapping("/{cadastralCode}/sub-parts/page")
    @Operation(summary = "Get a keyset-paginated page of lightweight cadastre sub-part summaries")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public SubPartPage getSubPartPage(
        @PathVariable String cadastralCode,
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "50") int limit
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findSubPartPage(orgId, cadastralCode, cursor, boundedHistoryLimit(limit));
    }

    @GetMapping("/{cadastralCode}/sub-parts/by-code/{subPartCode}/geometry")
    @Operation(summary = "Get geometry for one cadastre sub-part by the external sub-part code")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public SubPartGeometryDto getSubPartGeometryByCode(
        @PathVariable String cadastralCode,
        @PathVariable Integer subPartCode
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findSubPartGeometryByCode(orgId, cadastralCode, subPartCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre sub-part not found"));
    }

    @GetMapping("/{cadastralCode}/sub-parts/{subPartId}/geometry")
    @Operation(summary = "Get geometry for one cadastre sub-part")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR','CALLER','VIEWER')")
    public SubPartGeometryDto getSubPartGeometry(
        @PathVariable String cadastralCode,
        @PathVariable Long subPartId
    ) {
        UUID orgId = orgCtx.requiredOrganizationId();
        assertCanReadCadastre(orgId, cadastralCode);
        return cadastreRepository.findSubPartGeometry(orgId, cadastralCode, subPartId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre sub-part not found"));
    }

    private int boundedHistoryLimit(int limit) {
        if (limit < 1) return 1;
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private void assertCanReadCadastre(UUID orgId, String cadastralCode) {
        CadastreRepository.ParcelAssignmentScope scope = requireAssignmentScope(orgId, cadastralCode);
        ownerAuthorizationService.assertCanReadAnyAssignedSubject(scope.assignedSubjects());
    }

    private void assertCanMutateCadastre(UUID orgId, String cadastralCode) {
        CadastreRepository.ParcelAssignmentScope scope = requireAssignmentScope(orgId, cadastralCode);
        ownerAuthorizationService.assertCanMutateAnyAssignedSubject(scope.assignedSubjects());
    }

    private CadastreRepository.ParcelAssignmentScope requireAssignmentScope(UUID orgId, String cadastralCode) {
        CadastreRepository.ParcelAssignmentScope scope = cadastreRepository.findAssignmentScope(orgId, cadastralCode);
        if (!scope.parcelExists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadastre not found");
        }
        return scope;
    }
}
