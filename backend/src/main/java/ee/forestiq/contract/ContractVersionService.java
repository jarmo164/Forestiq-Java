package ee.forestiq.contract;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Size;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

@Service
public class ContractVersionService {
    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final ObjectMapper objectMapper;

    public ContractVersionService(DSLContext dsl, OrganizationContext orgCtx, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public VersionPlan plan(UUID orgId, UUID dealId, UUID replacesContractId, String changeReason, Checklist checklist) {
        validateChecklist(checklist);
        Record latestRow = dsl.fetchOne("""
            SELECT max(version_no) AS latest_version
              FROM contract_version_audit
             WHERE organization_id = ? AND deal_case_id = ?
            """, orgId, dealId);
        Integer latest = latestRow == null ? null : latestRow.get("latest_version", Integer.class);
        int next = latest == null ? 1 : latest + 1;
        if (next > 1) {
            if (replacesContractId == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Uus lepinguversioon peab viitama asendatavale varasemale lepingule.");
            }
            if (changeReason == null || changeReason.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "Uue lepinguversiooni muutmise põhjus on kohustuslik.");
            }
            Record previousRow = dsl.fetchOne("""
                SELECT EXISTS (
                    SELECT 1 FROM contract_version_audit
                     WHERE organization_id = ? AND deal_case_id = ? AND contract_id = ?
                ) AS valid_previous
                """, orgId, dealId, replacesContractId);
            Boolean validPrevious = previousRow == null ? Boolean.FALSE : previousRow.get("valid_previous", Boolean.class);
            if (!Boolean.TRUE.equals(validPrevious)) {
                throw new ResponseStatusException(BAD_REQUEST, "Asendatav leping ei kuulu selle tehingu versiooniahelasse.");
            }
        }
        return new VersionPlan(next, replacesContractId, normalize(changeReason), checklist);
    }

    @Transactional
    public VersionSummary record(UUID contractId, UUID dealId, UUID templateId, String templateHtml,
                                 Object sourceSnapshot, VersionPlan plan) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String checklistJson = json(plan.checklist());
        String sourceJson = json(sourceSnapshot);
        String templateHash = sha256(templateHtml == null ? "" : templateHtml);
        dsl.execute("""
            INSERT INTO contract_version_audit
              (organization_id, contract_id, deal_case_id, version_no, replaces_contract_id, change_reason,
               checklist, template_id, template_sha256, source_snapshot, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?)
            """, orgId, contractId, dealId, plan.versionNo(), plan.replacesContractId(), plan.changeReason(),
            checklistJson, templateId, templateHash, sourceJson, orgCtx.subject());
        return get(orgId, contractId);
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> versions(UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        return dsl.fetch("""
            SELECT contract_id, deal_case_id, version_no, replaces_contract_id, change_reason,
                   checklist, template_id, template_sha256, created_by_subject, created_at
              FROM contract_version_audit
             WHERE organization_id = ? AND deal_case_id = ?
             ORDER BY version_no DESC
            """, orgId, dealId).map(this::summary);
    }

    private VersionSummary get(UUID orgId, UUID contractId) {
        Record row = dsl.fetchOne("""
            SELECT contract_id, deal_case_id, version_no, replaces_contract_id, change_reason,
                   checklist, template_id, template_sha256, created_by_subject, created_at
              FROM contract_version_audit
             WHERE organization_id = ? AND contract_id = ?
            """, orgId, contractId);
        if (row == null) throw new IllegalStateException("Lepinguversiooni auditikirjet ei loodud.");
        return summary(row);
    }

    private VersionSummary summary(Record row) {
        JSONB checklistJson = row.get("checklist", JSONB.class);
        Checklist checklist;
        try {
            checklist = objectMapper.readValue(checklistJson.data(), Checklist.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Lepingu kontrollnimekirja auditikirje on vigane.", exception);
        }
        OffsetDateTime created = row.get("created_at", OffsetDateTime.class);
        return new VersionSummary(row.get("contract_id", UUID.class), row.get("deal_case_id", UUID.class),
            row.get("version_no", Integer.class), row.get("replaces_contract_id", UUID.class),
            row.get("change_reason", String.class), checklist, row.get("template_id", UUID.class),
            row.get("template_sha256", String.class), row.get("created_by_subject", String.class),
            created == null ? null : created.toInstant());
    }

    private static void validateChecklist(Checklist checklist) {
        if (checklist == null) throw new ResponseStatusException(BAD_REQUEST, "Kontrollnimekiri on kohustuslik.");
        List<String> missing = new ArrayList<>();
        if (!checklist.partiesConfirmed()) missing.add("osapooled");
        if (!checklist.parcelsConfirmed()) missing.add("kinnistud");
        if (!checklist.priceConfirmed()) missing.add("hind");
        if (!checklist.ibanConfirmed()) missing.add("IBAN");
        if (!checklist.acceptedOfferConfirmed()) missing.add("aktsepteeritud pakkumine");
        if (!checklist.templateConfirmed()) missing.add("lepingumall");
        if (!missing.isEmpty() && (checklist.exceptionReason() == null || checklist.exceptionReason().isBlank())) {
            throw new ResponseStatusException(BAD_REQUEST,
                "Kontrollnimekirjas on kinnitamata kohustuslikud punktid: " + String.join(", ", missing)
                    + ". Lisa õigustatud erandi põhjendus või kinnita punktid.");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Lepinguversiooni auditi serialiseerimine ebaõnnestus.", exception); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ei ole saadaval.", exception);
        }
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record Checklist(
        boolean partiesConfirmed,
        boolean parcelsConfirmed,
        boolean priceConfirmed,
        boolean ibanConfirmed,
        boolean acceptedOfferConfirmed,
        boolean templateConfirmed,
        @Size(max = 2000) String exceptionReason
    ) {}

    public record VersionPlan(int versionNo, UUID replacesContractId, String changeReason, Checklist checklist) {}

    public record VersionSummary(
        UUID contractId,
        UUID dealId,
        int versionNo,
        UUID replacesContractId,
        String changeReason,
        Checklist checklist,
        UUID templateId,
        String templateSha256,
        String createdBy,
        Instant createdAt
    ) {}
}
