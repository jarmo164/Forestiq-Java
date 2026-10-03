package ee.forestiq.contract;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PRECONDITION_FAILED;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class ContractSigningService {
    private static final Set<OrganizationRole> RESPONSIBLE_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);
    private static final Set<String> STATUSES = Set.of("PREPARING", "SENT_FOR_SIGNATURE", "SIGNED", "CANCELLED");
    private static final Set<String> SIGNED_CONTENT_TYPES = Set.of(
        "application/pdf",
        "application/vnd.etsi.asic-e+zip",
        "application/x-asice"
    );

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final OrganizationIdentityDirectory identityDirectory;

    public ContractSigningService(DSLContext dsl, OrganizationContext orgCtx,
                                  OwnerAuthorizationService ownerAuthorization,
                                  OrganizationIdentityDirectory identityDirectory) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.identityDirectory = identityDirectory;
    }

    @Transactional
    public SigningState ensurePreparing(UUID contractId, UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        ContractAccess access = requireContract(orgId, contractId);
        if (!access.dealId().equals(dealId)) {
            throw new ResponseStatusException(BAD_REQUEST, "Leping ei kuulu sellele tehingule.");
        }
        Record current = stateRow(orgId, contractId);
        if (current != null) return state(current);
        String subject = orgCtx.subject();
        Instant now = Instant.now();
        Instant dueAt = now.plus(7, ChronoUnit.DAYS);
        dsl.execute("""
            INSERT INTO contract_signing_state
                (organization_id, contract_id, deal_case_id, status, responsible_subject, due_at,
                 status_at, updated_by_subject)
            VALUES (?, ?, ?, 'PREPARING', ?, ?, ?, ?)
            """, orgId, contractId, dealId, subject, dueAt, now, subject);
        addEvent(orgId, contractId, dealId, "CREATED", null, "PREPARING", now, subject, dueAt,
            "Kontrollitud lepinguversioon loodi.", null);
        return requireState(orgId, contractId);
    }

    @Transactional(readOnly = true)
    public List<SigningState> byDeal(UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireDealAccess(orgId, dealId);
        return dsl.fetch("""
            SELECT s.contract_id, s.deal_case_id, s.status, s.responsible_subject, s.due_at, s.status_at,
                   s.stall_reason, s.signed_document_id, s.signed_external_url, s.updated_by_subject, s.updated_at,
                   v.version_no
              FROM contract_signing_state s
              JOIN contract_version_audit v
                ON v.organization_id = s.organization_id AND v.contract_id = s.contract_id
             WHERE s.organization_id = ? AND s.deal_case_id = ?
             ORDER BY v.version_no DESC, s.updated_at DESC
            """, orgId, dealId).map(this::state);
    }

    @Transactional(readOnly = true)
    public SigningState get(UUID contractId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireContract(orgId, contractId);
        return requireState(orgId, contractId);
    }

    @Transactional
    public SigningState transition(UUID contractId, TransitionRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        ContractAccess access = requireContract(orgId, contractId);
        Record currentRow = stateRowForUpdate(orgId, contractId);
        if (currentRow == null) {
            ensurePreparing(contractId, access.dealId());
            currentRow = stateRowForUpdate(orgId, contractId);
        }
        String current = currentRow.get("status", String.class);
        String next = normalizeStatus(request.status());
        Instant effectiveAt = request.effectiveAt() == null ? Instant.now() : request.effectiveAt();
        if (effectiveAt.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            throw new ResponseStatusException(BAD_REQUEST, "Olekumuutuse aeg ei tohi olla tulevikus.");
        }
        if (("SIGNED".equals(current) || "CANCELLED".equals(current)) && !current.equals(next)) {
            throw new ResponseStatusException(PRECONDITION_FAILED, "Lõpetatud allkirjastusprotsessi olekut ei saa muuta.");
        }
        validateTransition(current, next);
        String responsible = request.responsibleSubject().trim();
        identityDirectory.requireAssignableUser(orgId, responsible, RESPONSIBLE_ROLES);
        Instant dueAt = request.dueAt();
        if (dueAt == null) throw new ResponseStatusException(BAD_REQUEST, "Lepingu tähtaeg on kohustuslik.");
        String reason = trim(request.reason());
        Reference ref = validateReference(orgId, access.ownerId(), next, request.signedDocumentId(), request.signedExternalUrl());
        if ("CANCELLED".equals(next) && reason == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Katkestamisel on põhjus kohustuslik.");
        }

        dsl.execute("""
            UPDATE contract_signing_state
               SET status = ?, responsible_subject = ?, due_at = ?, status_at = ?, stall_reason = ?,
                   signed_document_id = ?, signed_external_url = ?, updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND contract_id = ?
            """, next, responsible, dueAt, effectiveAt, reason, ref.documentId(), ref.externalUrl(),
            orgCtx.subject(), orgId, contractId);
        addEvent(orgId, contractId, access.dealId(), "STATUS_CHANGED", current, next, effectiveAt,
            responsible, dueAt, reason, ref.kind());
        return requireState(orgId, contractId);
    }

    @Transactional(readOnly = true)
    public List<SigningEvent> history(UUID contractId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireContract(orgId, contractId);
        return dsl.fetch("""
            SELECT id, contract_id, deal_case_id, event_type, from_status, to_status, effective_at,
                   responsible_subject, due_at, reason, reference_kind, created_by_subject, created_at
              FROM contract_signing_event
             WHERE organization_id = ? AND contract_id = ?
             ORDER BY created_at DESC, id DESC
            """, orgId, contractId).map(row -> new SigningEvent(
            row.get("id", UUID.class), row.get("contract_id", UUID.class), row.get("deal_case_id", UUID.class),
            row.get("event_type", String.class), row.get("from_status", String.class), row.get("to_status", String.class),
            instant(row, "effective_at"), row.get("responsible_subject", String.class), instant(row, "due_at"),
            row.get("reason", String.class), row.get("reference_kind", String.class),
            row.get("created_by_subject", String.class), instant(row, "created_at")
        ));
    }

    @Transactional(readOnly = true)
    public SignedReference signedReference(UUID contractId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        ContractAccess access = requireContract(orgId, contractId);
        Record row = stateRow(orgId, contractId);
        if (row == null || !"SIGNED".equals(row.get("status", String.class))) {
            throw new ResponseStatusException(NOT_FOUND, "Allkirjastatud viidet ei ole.");
        }
        UUID documentId = row.get("signed_document_id", UUID.class);
        if (documentId != null) {
            Record doc = dsl.fetchOne("""
                SELECT id, original_file_name, content_type, byte_size
                  FROM document WHERE organization_id = ? AND id = ?
                """, orgId, documentId);
            if (doc == null) throw new ResponseStatusException(NOT_FOUND, "Allkirjastatud dokumenti ei leitud.");
            return new SignedReference("DOCUMENT", null, documentId, doc.get("original_file_name", String.class),
                doc.get("content_type", String.class), doc.get("byte_size", Long.class));
        }
        return new SignedReference("EXTERNAL", row.get("signed_external_url", String.class), null, null, null, null);
    }

    private ContractAccess requireContract(UUID orgId, UUID contractId) {
        Record row = dsl.fetchOne("""
            SELECT v.deal_case_id, d.owner_id, o.assigned_to_subject
              FROM contract_version_audit v
              JOIN deal_case d ON d.organization_id = v.organization_id AND d.id = v.deal_case_id
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE v.organization_id = ? AND v.contract_id = ?
            """, orgId, contractId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Lepingut ei leitud.");
        ownerAuthorization.assertCanReadAssignedSubject(row.get("assigned_to_subject", String.class));
        return new ContractAccess(row.get("deal_case_id", UUID.class), row.get("owner_id", UUID.class));
    }

    private void requireDealAccess(UUID orgId, UUID dealId) {
        Record row = dsl.fetchOne("""
            SELECT o.assigned_to_subject
              FROM deal_case d JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE d.organization_id = ? AND d.id = ?
            """, orgId, dealId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Tehingut ei leitud.");
        ownerAuthorization.assertCanReadAssignedSubject(row.get("assigned_to_subject", String.class));
    }

    private Reference validateReference(UUID orgId, UUID ownerId, String status, UUID documentId, String externalUrl) {
        boolean hasDocument = documentId != null;
        String url = trim(externalUrl);
        boolean hasUrl = url != null;
        if (hasDocument && hasUrl) throw new ResponseStatusException(BAD_REQUEST, "Vali fail või välisviide, mitte mõlemad.");
        if ("SIGNED".equals(status) && !hasDocument && !hasUrl) {
            throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud olek vajab allkirjastatud faili või turvalist välisviidet.");
        }
        if (!"SIGNED".equals(status) && (hasDocument || hasUrl)) {
            throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud viite saab lisada ainult allkirjastatud olekus.");
        }
        if (hasDocument) {
            Record doc = dsl.fetchOne("""
                SELECT owner_id, content_type, original_file_name
                  FROM document WHERE organization_id = ? AND id = ?
                """, orgId, documentId);
            if (doc == null) throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud faili ei leitud organisatsioonist.");
            UUID documentOwner = doc.get("owner_id", UUID.class);
            if (documentOwner != null && !documentOwner.equals(ownerId)) {
                throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud fail ei kuulu lepingu omanikule.");
            }
            String contentType = doc.get("content_type", String.class);
            if (contentType == null || !SIGNED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                throw new ResponseStatusException(BAD_REQUEST, "Allkirjastatud fail peab olema PDF või ASiC-E konteiner.");
            }
            return new Reference(documentId, null, "DOCUMENT");
        }
        if (hasUrl) {
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(BAD_REQUEST, "Välisviide peab olema korrektne HTTPS-aadress.");
            }
            return new Reference(null, url, "EXTERNAL");
        }
        return new Reference(null, null, null);
    }

    private Record stateRow(UUID orgId, UUID contractId) {
        return dsl.fetchOne("""
            SELECT s.contract_id, s.deal_case_id, s.status, s.responsible_subject, s.due_at, s.status_at,
                   s.stall_reason, s.signed_document_id, s.signed_external_url, s.updated_by_subject, s.updated_at,
                   v.version_no
              FROM contract_signing_state s
              JOIN contract_version_audit v ON v.organization_id = s.organization_id AND v.contract_id = s.contract_id
             WHERE s.organization_id = ? AND s.contract_id = ?
            """, orgId, contractId);
    }

    private Record stateRowForUpdate(UUID orgId, UUID contractId) {
        return dsl.fetchOne("SELECT * FROM contract_signing_state WHERE organization_id = ? AND contract_id = ? FOR UPDATE", orgId, contractId);
    }

    private SigningState requireState(UUID orgId, UUID contractId) {
        Record row = stateRow(orgId, contractId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Lepingu allkirjastusolekut ei leitud.");
        return state(row);
    }

    private SigningState state(Record row) {
        Instant due = instant(row, "due_at");
        String status = row.get("status", String.class);
        String storedReason = row.get("stall_reason", String.class);
        boolean overdue = due != null && due.isBefore(Instant.now()) && !Set.of("SIGNED", "CANCELLED").contains(status);
        String stall = storedReason != null ? storedReason : overdue
            ? ("PREPARING".equals(status) ? "Lepingu ettevalmistamise tähtaeg on ületatud." : "Allkirjastamise tähtaeg on ületatud.")
            : null;
        Integer version = row.get("version_no", Integer.class);
        return new SigningState(row.get("contract_id", UUID.class), row.get("deal_case_id", UUID.class),
            version == null ? 1 : version, status, row.get("responsible_subject", String.class), due,
            instant(row, "status_at"), stall, row.get("signed_document_id", UUID.class) != null,
            row.get("signed_external_url", String.class) != null, overdue,
            row.get("updated_by_subject", String.class), instant(row, "updated_at"));
    }

    private void addEvent(UUID orgId, UUID contractId, UUID dealId, String eventType, String from, String to,
                          Instant effectiveAt, String responsible, Instant dueAt, String reason, String referenceKind) {
        dsl.execute("""
            INSERT INTO contract_signing_event
                (organization_id, contract_id, deal_case_id, event_type, from_status, to_status, effective_at,
                 responsible_subject, due_at, reason, reference_kind, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, orgId, contractId, dealId, eventType, from, to, effectiveAt, responsible, dueAt,
            trim(reason), referenceKind, orgCtx.subject());
    }

    private static void validateTransition(String from, String to) {
        if (from.equals(to)) return;
        boolean allowed = switch (from) {
            case "PREPARING" -> Set.of("SENT_FOR_SIGNATURE", "CANCELLED").contains(to);
            case "SENT_FOR_SIGNATURE" -> Set.of("SIGNED", "CANCELLED").contains(to);
            case "SIGNED", "CANCELLED" -> false;
            default -> false;
        };
        if (!allowed) throw new ResponseStatusException(PRECONDITION_FAILED, "Lepingu olekumuutus ei ole lubatud: " + from + " → " + to);
    }

    private static String normalizeStatus(String raw) {
        String status = raw == null ? "" : raw.trim().toUpperCase();
        if (!STATUSES.contains(status)) throw new ResponseStatusException(BAD_REQUEST, "Tundmatu lepingu olek.");
        return status;
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record ContractAccess(UUID dealId, UUID ownerId) {}
    private record Reference(UUID documentId, String externalUrl, String kind) {}

    public record TransitionRequest(
        @NotBlank String status,
        @NotBlank @Size(max = 100) String responsibleSubject,
        @NotNull Instant dueAt,
        Instant effectiveAt,
        @Size(max = 2000) String reason,
        UUID signedDocumentId,
        @Size(max = 2000) String signedExternalUrl
    ) {}

    public record SigningState(
        UUID contractId,
        UUID dealId,
        int versionNo,
        String status,
        String responsibleSubject,
        Instant dueAt,
        Instant statusAt,
        String stallReason,
        boolean hasSignedDocument,
        boolean hasSignedExternalReference,
        boolean overdue,
        String updatedBy,
        Instant updatedAt
    ) {}

    public record SigningEvent(
        UUID id,
        UUID contractId,
        UUID dealId,
        String eventType,
        String fromStatus,
        String toStatus,
        Instant effectiveAt,
        String responsibleSubject,
        Instant dueAt,
        String reason,
        String referenceKind,
        String createdBy,
        Instant createdAt
    ) {}

    public record SignedReference(String kind, String externalUrl, UUID documentId, String fileName,
                                  String contentType, Long byteSize) {}
}
