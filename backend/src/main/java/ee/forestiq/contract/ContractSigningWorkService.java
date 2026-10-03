package ee.forestiq.contract;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class ContractSigningWorkService {
    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;

    public ContractSigningWorkService(DSLContext dsl, OrganizationContext orgCtx,
                                      OwnerAuthorizationService ownerAuthorization) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
    }

    @Transactional(readOnly = true)
    public List<SigningWorkItem> active() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String ownerRestriction = ownerAuthorization.assignedSubjectRestriction().orElse(null);
        String userScope = ownerAuthorization.hasOrganizationWideAccess() ? null : orgCtx.subject();
        return dsl.fetch("""
            SELECT s.contract_id, s.deal_case_id, v.version_no, o.id AS owner_id, o.display_name AS owner_name,
                   s.status, s.responsible_subject, s.due_at, s.status_at, s.stall_reason,
                   s.updated_at, d.sales_subject
              FROM contract_signing_state s
              JOIN contract_version_audit v
                ON v.organization_id = s.organization_id AND v.contract_id = s.contract_id
              JOIN deal_case d ON d.organization_id = s.organization_id AND d.id = s.deal_case_id
              JOIN owner o ON o.organization_id = d.organization_id AND o.id = d.owner_id
             WHERE s.organization_id = ?
               AND s.status IN ('PREPARING','SENT_FOR_SIGNATURE')
               AND (CAST(? AS varchar) IS NULL OR o.assigned_to_subject = CAST(? AS varchar))
               AND (CAST(? AS varchar) IS NULL OR d.sales_subject = CAST(? AS varchar) OR s.responsible_subject = CAST(? AS varchar))
             ORDER BY s.due_at ASC, v.version_no DESC, s.contract_id
             LIMIT 500
            """, orgId, ownerRestriction, ownerRestriction, userScope, userScope, userScope).map(this::item);
    }

    private SigningWorkItem item(Record row) {
        Instant dueAt = instant(row, "due_at");
        String stored = row.get("stall_reason", String.class);
        boolean overdue = dueAt != null && dueAt.isBefore(Instant.now());
        String stall = stored;
        if (stall == null && overdue) {
            stall = "PREPARING".equals(row.get("status", String.class))
                ? "Lepingu ettevalmistamise tähtaeg on ületatud."
                : "Allkirjastamise tähtaeg on ületatud.";
        }
        return new SigningWorkItem(
            row.get("contract_id", UUID.class), row.get("deal_case_id", UUID.class), row.get("version_no", Integer.class),
            row.get("owner_id", UUID.class), row.get("owner_name", String.class), row.get("status", String.class),
            row.get("responsible_subject", String.class), dueAt, instant(row, "status_at"), stall, overdue,
            row.get("sales_subject", String.class), instant(row, "updated_at")
        );
    }

    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record SigningWorkItem(
        UUID contractId,
        UUID dealId,
        int versionNo,
        UUID ownerId,
        String ownerName,
        String status,
        String responsibleSubject,
        Instant dueAt,
        Instant statusAt,
        String stallReason,
        boolean overdue,
        String salesSubject,
        Instant updatedAt
    ) {}
}
