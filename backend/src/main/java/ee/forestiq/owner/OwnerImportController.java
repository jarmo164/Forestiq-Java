package ee.forestiq.owner;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ee.forestiq.owner.OwnerImportService.ImportBatchSummary;
import ee.forestiq.owner.OwnerImportService.ImportCommitResult;
import ee.forestiq.owner.OwnerImportService.ImportInspect;
import ee.forestiq.owner.OwnerImportService.ImportMapping;
import ee.forestiq.owner.OwnerImportService.ImportPreview;

@RestController
@RequestMapping("/api/v1/owners/imports")
@Tag(name = "Owner imports", description = "Safe organization-scoped CSV/XLSX owner onboarding")
@PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
public class OwnerImportController {

    private final OwnerImportService importService;

    public OwnerImportController(OwnerImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Inspect owner import headers and suggest a column mapping without writing data")
    public ImportInspect inspect(@RequestPart("file") MultipartFile file) {
        return importService.inspect(file);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Validate an owner import with explicit column mapping and no writes")
    public ImportPreview preview(@RequestPart("file") MultipartFile file,
                                 @RequestPart("mapping") ImportMapping mapping) {
        return importService.preview(file, mapping);
    }

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Commit only READY rows from the exact file confirmed by preview SHA-256")
    public ImportCommitResult commit(@RequestPart("file") MultipartFile file,
                                     @RequestPart("mapping") ImportMapping mapping,
                                     @RequestPart("confirmedSha256") String confirmedSha256) {
        return importService.commit(file, mapping, confirmedSha256);
    }

    @GetMapping("/batches")
    @Operation(summary = "List recent owner import batches for the active organization")
    public List<ImportBatchSummary> batches() {
        return importService.recentBatches();
    }

    @GetMapping(value = "/batches/{batchId}/errors.csv", produces = "text/csv")
    @Operation(summary = "Download duplicate and invalid rows recorded for an import batch")
    public ResponseEntity<byte[]> batchErrors(@PathVariable UUID batchId) {
        byte[] body = importService.batchErrorsCsv(batchId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"owner-import-" + batchId + "-errors.csv\"")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(body);
    }
}
