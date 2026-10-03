package ee.forestiq.contract;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ee.forestiq.contract.ContractSignedFileService.DownloadedSignedFile;
import ee.forestiq.contract.ContractSignedFileService.UploadedSignedFile;
import ee.forestiq.contract.ContractSigningService.SignedReference;
import ee.forestiq.contract.ContractSigningService.SigningEvent;
import ee.forestiq.contract.ContractSigningService.SigningState;
import ee.forestiq.contract.ContractSigningService.TransitionRequest;
import ee.forestiq.contract.ContractSigningWorkService.SigningWorkItem;

@RestController
@RequestMapping("/api/v1/contract-signing")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER','CALLER')")
public class ContractSigningController {
    private final ContractSigningService service;
    private final ContractSigningWorkService workService;
    private final ContractSignedFileService fileService;

    public ContractSigningController(ContractSigningService service, ContractSigningWorkService workService,
                                     ContractSignedFileService fileService) {
        this.service = service;
        this.workService = workService;
        this.fileService = fileService;
    }

    @GetMapping("/active")
    public List<SigningWorkItem> active() { return workService.active(); }

    @GetMapping("/deals/{dealId}")
    public List<SigningState> byDeal(@PathVariable UUID dealId) { return service.byDeal(dealId); }

    @GetMapping("/contracts/{contractId}")
    public SigningState state(@PathVariable UUID contractId) { return service.get(contractId); }

    @PutMapping("/contracts/{contractId}")
    public SigningState transition(@PathVariable UUID contractId, @Valid @RequestBody TransitionRequest request) {
        return service.transition(contractId, request);
    }

    @PostMapping(value = "/contracts/{contractId}/signed-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadedSignedFile uploadSignedFile(@PathVariable UUID contractId, @RequestParam("file") MultipartFile file) {
        return fileService.upload(contractId, file);
    }

    @GetMapping("/contracts/{contractId}/signed-file")
    public ResponseEntity<byte[]> downloadSignedFile(@PathVariable UUID contractId) {
        DownloadedSignedFile file = fileService.download(contractId);
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(file.contentType()); }
        catch (Exception ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        String safeName = file.fileName() == null ? "signed-document" : file.fileName().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
            .contentType(mediaType)
            .body(file.bytes());
    }

    @GetMapping("/contracts/{contractId}/history")
    public List<SigningEvent> history(@PathVariable UUID contractId) { return service.history(contractId); }

    @GetMapping("/contracts/{contractId}/signed-reference")
    public SignedReference signedReference(@PathVariable UUID contractId) { return service.signedReference(contractId); }
}
