package ee.forestiq.contract;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class ContractSignedFileService {
    private static final long MAX_BYTES = 25L * 1024L * 1024L;
    private static final Set<String> CONTENT_TYPES = Set.of(
        "application/pdf",
        "application/vnd.etsi.asic-e+zip",
        "application/x-asice"
    );

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final ContractStorageService storage;

    public ContractSignedFileService(DSLContext dsl, OrganizationContext orgCtx,
                                     OwnerAuthorizationService ownerAuthorization,
                                     ContractStorageService storage) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.storage = storage;
    }

    @Transactional
    public UploadedSignedFile upload(UUID contractId, MultipartFile file) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Access access = requireAccess(orgId, contractId);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud fail on kohustuslik.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud faili ülempiir on 25 MB.");
        String contentType = normalizeContentType(file.getContentType());
        if (!CONTENT_TYPES.contains(contentType)) throw new ResponseStatusException(BAD_REQUEST, "Lubatud on PDF või ASiC-E konteiner.");
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
            ? "signed-document" : file.getOriginalFilename().trim();
        UUID documentId = UUID.randomUUID();
        try {
            byte[] bytes = file.getBytes();
            String objectKey = storage.putSignedDocument(orgId, contractId, documentId, fileName, contentType, bytes);
            dsl.execute("""
                INSERT INTO document
                    (id, organization_id, owner_id, object_key, original_file_name, content_type,
                     byte_size, created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, orgId, access.ownerId(), objectKey, fileName, contentType,
                (long) bytes.length, orgCtx.subject());
            return new UploadedSignedFile(documentId, fileName, contentType, bytes.length);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Allkirjastatud faili salvestamine ebaõnnestus.", exception);
        }
    }

    @Transactional(readOnly = true)
    public DownloadedSignedFile download(UUID contractId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireAccess(orgId, contractId);
        Record row = dsl.fetchOne("""
            SELECT d.object_key, d.original_file_name, d.content_type
              FROM contract_signing_state s
              JOIN document d ON d.organization_id = s.organization_id AND d.id = s.signed_document_id
             WHERE s.organization_id = ? AND s.contract_id = ? AND s.status = 'SIGNED'
            """, orgId, contractId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Allkirjastatud faili ei leitud.");
        byte[] bytes = storage.get(row.get("object_key", String.class));
        return new DownloadedSignedFile(row.get("original_file_name", String.class), row.get("content_type", String.class), bytes);
    }

    private Access requireAccess(UUID orgId, UUID contractId) {
        Record row = dsl.fetchOne("""
            SELECT d.owner_id, o.assigned_to_subject
              FROM contract_version_audit v
              JOIN deal_case d ON d.organization_id = v.organization_id AND d.id = v.deal_case_id
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE v.organization_id = ? AND v.contract_id = ?
            """, orgId, contractId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Lepingut ei leitud.");
        ownerAuthorization.assertCanReadAssignedSubject(row.get("assigned_to_subject", String.class));
        return new Access(row.get("owner_id", UUID.class));
    }

    private static String normalizeContentType(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private record Access(UUID ownerId) {}
    public record UploadedSignedFile(UUID documentId, String fileName, String contentType, long byteSize) {}
    public record DownloadedSignedFile(String fileName, String contentType, byte[] bytes) {}
}
