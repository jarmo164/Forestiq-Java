package ee.forestiq.contract;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.contract.ContractConfigurationService.CompanyProfile;
import ee.forestiq.contract.ContractConfigurationService.ContractTemplate;
import ee.forestiq.contract.ContractPdfRenderer.Company;
import ee.forestiq.contract.ContractPdfRenderer.ContractTemplateContext;
import ee.forestiq.contract.ContractPdfRenderer.Parcel;
import ee.forestiq.contract.ContractPdfRenderer.Seller;
import ee.forestiq.contract.ContractVersionService.Checklist;
import ee.forestiq.contract.ContractVersionService.VersionPlan;
import ee.forestiq.contract.ContractVersionService.VersionSummary;
import ee.forestiq.contract.DealContractSourceService.DealContractDraft;
import ee.forestiq.contract.DealContractSourceService.DealSourceSnapshot;
import ee.forestiq.owner.OwnerDtos.ContractHistoryItem;
import ee.forestiq.owner.OwnerDtos.ContractSearchFilter;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contracts", description = "Editable template based PDF contract generation, downloads and history")
public class ContractController {

    private static final Logger log = LoggerFactory.getLogger(ContractController.class);
    private static final String DEAL_SOURCE_TYPE = "DEAL_ACCEPTED_OFFER";

    private final OrganizationContext orgCtx;
    private final ObjectMapper objectMapper;
    private final ContractConfigurationService configurationService;
    private final ContractPdfRenderer pdfRenderer;
    private final ContractStorageService storageService;
    private final ContractRepository contractRepository;
    private final DealContractSourceService dealSourceService;
    private final ContractVersionService versionService;

    public ContractController(OrganizationContext orgCtx, ObjectMapper objectMapper,
                              ContractConfigurationService configurationService, ContractPdfRenderer pdfRenderer,
                              ContractStorageService storageService, ContractRepository contractRepository,
                              DealContractSourceService dealSourceService, ContractVersionService versionService) {
        this.orgCtx = orgCtx;
        this.objectMapper = objectMapper;
        this.configurationService = configurationService;
        this.pdfRenderer = pdfRenderer;
        this.storageService = storageService;
        this.contractRepository = contractRepository;
        this.dealSourceService = dealSourceService;
        this.versionService = versionService;
    }

    @GetMapping("/deals/{dealId}/draft")
    @Operation(summary = "Build a reviewable contract draft from a WON deal and its accepted offer")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public DealContractDraft dealDraft(@PathVariable UUID dealId) {
        return dealSourceService.draft(dealId);
    }

    @GetMapping("/deals/{dealId}/versions")
    @Operation(summary = "List immutable controlled contract versions for a deal")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<VersionSummary> versions(@PathVariable UUID dealId) {
        dealSourceService.draft(dealId);
        return versionService.versions(dealId);
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate and persist a controlled deal-derived PDF contract version")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public GeneratedContract generate(@Valid @RequestBody GenerateContractRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        DealSourceSnapshot source = requireMatchingSource(request);
        ContractTemplate template = configurationService.resolveTemplate(orgId, request.templateId());
        CompanyProfile company = configurationService.resolveCompany(orgId,
            request.companyProfileId() != null ? request.companyProfileId() : template.companyProfileId());
        VersionPlan versionPlan = versionService.plan(orgId, source.dealId(), request.replacesContractId(), request.changeReason(), request.checklist());
        byte[] pdf = pdfRenderer.render(template.htmlTemplate(), context(request, company));
        UUID historyId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        String fileName = safeFileName((request.contractNumber() == null || request.contractNumber().isBlank()
            ? "leping" : request.contractNumber()) + "-v" + versionPlan.versionNo() + ".pdf");
        try {
            String data = objectMapper.writeValueAsString(new StoredContractAudit(DEAL_SOURCE_TYPE, request, source));
            contractRepository.storeGeneratedContract(
                orgId,
                historyId,
                contractId,
                data,
                request.seller().name(),
                company.legalName(),
                request.contractNumber(),
                cadastres(request.parcels()),
                pdf,
                company.id(),
                template.id(),
                fileName,
                MediaType.APPLICATION_PDF_VALUE);
        } catch (Exception exception) {
            throw new ContractPdfRenderer.ContractGenerationException("Lepingu ajaloo talletamine ebaõnnestus", exception);
        }
        try {
            String objectKey = storageService.putPdf(orgId, contractId, fileName, pdf);
            contractRepository.updateObjectKey(orgId, contractId, objectKey);
        } catch (RuntimeException exception) {
            log.warn("Failed to store contract {} in object storage; database copy remains authoritative and downloadable", contractId, exception);
        }
        VersionSummary version = versionService.record(contractId, source.dealId(), template.id(), template.htmlTemplate(), source, versionPlan);
        return new GeneratedContract(contractId, historyId, fileName, template.id(), company.id(),
            source.dealId(), source.offerEntryId(), version.versionNo());
    }

    @PostMapping("/preview")
    @Operation(summary = "Render a deal-derived PDF preview without storing a contract")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public ResponseEntity<byte[]> preview(@Valid @RequestBody GenerateContractRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireMatchingSource(request);
        ContractTemplate template = configurationService.resolveTemplate(orgId, request.templateId());
        CompanyProfile company = configurationService.resolveCompany(orgId,
            request.companyProfileId() != null ? request.companyProfileId() : template.companyProfileId());
        return pdfResponse(pdfRenderer.render(template.htmlTemplate(), context(request, company)), "lepingu-eelvaade.pdf");
    }

    @GetMapping("/{contractId}/download")
    @Operation(summary = "Download a generated contract with verified database fallback")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public ResponseEntity<byte[]> download(@PathVariable UUID contractId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        var contractFile = contractRepository.findContractFile(orgId, contractId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lepingut ei leitud"));
        byte[] data = resolveDownloadData(contractId, contractFile);
        return pdfResponse(data, contractFile.fileName() == null ? "leping-" + contractId + ".pdf" : contractFile.fileName());
    }

    @PostMapping("/history/search")
    @Operation(summary = "Search contract history")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<ContractHistoryItem> searchHistory(@RequestBody ContractSearchFilter filter) {
        UUID orgId = orgCtx.requiredOrganizationId();
        return contractRepository.searchHistory(orgId, filter);
    }

    @DeleteMapping("/history/{id}")
    @Operation(summary = "Delete contract history entry")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHistory(@PathVariable UUID id) {
        contractRepository.deleteHistory(orgCtx.requiredOrganizationId(), id);
    }

    private DealSourceSnapshot requireMatchingSource(GenerateContractRequest request) {
        DealSourceSnapshot source = dealSourceService.validateSource(request.dealId(), request.offerEntryId());
        if (!sameMoney(request.price(), source.acceptedPrice())) {
            throw sourceMismatch("Lepingu hind peab võrduma aktsepteeritud pakkumise hinnaga.");
        }
        if (!Objects.equals(normalize(request.terms()), normalize(source.acceptedTerms()))) {
            throw sourceMismatch("Lepingu tingimused peavad vastama aktsepteeritud pakkumise tingimustele.");
        }
        if (!Objects.equals(normalize(request.seller().name()), normalize(source.seller().name()))
            || !Objects.equals(normalize(request.seller().code()), normalize(source.seller().code()))
            || !Objects.equals(normalize(request.seller().address()), normalize(source.seller().address()))) {
            throw sourceMismatch("Lepingu müüja peab vastama tehingu omanikule.");
        }
        List<String> requestedParcels = request.parcels().stream().map(ContractController::parcelIdentity).sorted().toList();
        List<String> sourceParcels = source.parcels().stream()
            .map(parcel -> parcelIdentity(parcel.cadastralCode(), parcel.areaHectares())).sorted().toList();
        if (!requestedParcels.equals(sourceParcels)) {
            throw sourceMismatch("Lepingu kinnistud peavad vastama võidetud tehingu kinnistutele.");
        }
        return source;
    }

    private byte[] resolveDownloadData(UUID contractId, ContractRepository.ContractFile contractFile) {
        byte[] databaseCopy = contractFile.data();
        if (contractFile.objectKey() != null && !contractFile.objectKey().isBlank()) {
            try {
                byte[] objectCopy = storageService.get(contractFile.objectKey());
                if (objectCopy != null && objectCopy.length > 0) {
                    if (databaseCopy == null || databaseCopy.length == 0) return objectCopy;
                    if (MessageDigest.isEqual(sha256(objectCopy), sha256(databaseCopy))) return objectCopy;
                    log.error("Contract {} object-storage copy failed SHA-256 verification; serving immutable database copy", contractId);
                } else {
                    log.warn("Contract {} object-storage key {} returned no data; serving database copy", contractId, contractFile.objectKey());
                }
            } catch (RuntimeException exception) {
                log.warn("Contract {} object-storage read failed for key {}; serving database copy", contractId, contractFile.objectKey(), exception);
            }
        }
        if (databaseCopy == null || databaseCopy.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Lepingu objektisalvestuse koopia ei ole saadaval ja andmebaasi varukoopia puudub.");
        }
        return databaseCopy;
    }

    private static ResponseStatusException sourceMismatch(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String parcelIdentity(ContractParcel parcel) {
        return parcelIdentity(parcel.cadastralCode(), parcel.areaHectares());
    }

    private static String parcelIdentity(String cadastralCode, BigDecimal areaHectares) {
        String area = areaHectares == null ? "" : areaHectares.stripTrailingZeros().toPlainString();
        return normalize(cadastralCode) + "|" + area;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String cadastres(List<ContractParcel> parcels) {
        return parcels.stream()
            .map(ContractParcel::cadastralCode)
            .reduce("", (left, right) -> left.isBlank() ? right : left + ", " + right);
    }

    private ContractTemplateContext context(GenerateContractRequest request, CompanyProfile company) {
        List<Parcel> parcels = request.parcels() == null ? List.of() : request.parcels().stream()
            .map(parcel -> new Parcel(parcel.cadastralCode(), parcel.address(), parcel.areaHectares())).toList();
        ContractSeller seller = request.seller() == null ? new ContractSeller("", "", "", "") : request.seller();
        return new ContractTemplateContext(request.contractNumber(), request.contractDate(), request.place(), request.price(), request.terms(),
            new Seller(seller.name(), seller.code(), seller.address(), seller.iban()),
            new Company(company.legalName(), company.registryCode(), company.address(), company.email(), company.phone(),
                company.representativeName(), company.representativeRole()),
            parcels);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String fileName) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFileName(fileName) + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record GenerateContractRequest(
        UUID templateId,
        UUID companyProfileId,
        @NotBlank String contractNumber,
        LocalDate contractDate,
        String place,
        @PositiveOrZero BigDecimal price,
        @Size(max = 4000) String terms,
        @Valid @NotNull ContractSeller seller,
        @NotEmpty List<@Valid ContractParcel> parcels,
        @NotNull UUID dealId,
        @NotNull UUID offerEntryId,
        @Valid @NotNull Checklist checklist,
        @Size(max = 2000) String changeReason,
        UUID replacesContractId
    ) { }

    public record ContractSeller(@NotBlank String name, String code, String address, String iban) { }
    public record ContractParcel(@NotBlank String cadastralCode, String address, @PositiveOrZero BigDecimal areaHectares) { }
    public record GeneratedContract(UUID contractId, UUID historyId, String fileName, UUID templateId,
                                    UUID companyProfileId, UUID dealId, UUID offerEntryId, int versionNo) { }
    public record StoredContractAudit(String sourceType, GenerateContractRequest request, DealSourceSnapshot source) { }
}
