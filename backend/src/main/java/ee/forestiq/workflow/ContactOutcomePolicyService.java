package ee.forestiq.workflow;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/** Persistence adapter for the workflow-owned contact outcome policy. */
@Service
class ContactOutcomePolicyService implements ContactOutcomePolicy {

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;

    ContactOutcomePolicyService(DSLContext dsl, OrganizationContext organizationContext) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
    }

    @Override
    @Transactional
    public List<Outcome> list() {
        UUID organizationId = organizationContext.requiredOrganizationId();
        ensureDefaults(organizationId);
        return dsl.fetch("""
            SELECT code, label, requires_reason, requires_next_activity, active, sort_order
            FROM contact_outcome_definition
            WHERE organization_id = ?
            ORDER BY sort_order, label, code
            """, organizationId).map(this::map);
    }

    @Override
    @Transactional
    public Outcome update(String rawCode, Update command) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        String code = normalizeCode(rawCode);
        String label = command.label().trim();
        dsl.execute("""
            INSERT INTO contact_outcome_definition
                (organization_id, code, label, requires_reason, requires_next_activity, active, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (organization_id, code) DO UPDATE SET
                label = EXCLUDED.label,
                requires_reason = EXCLUDED.requires_reason,
                requires_next_activity = EXCLUDED.requires_next_activity,
                active = EXCLUDED.active,
                sort_order = EXCLUDED.sort_order
            """, organizationId, code, label, command.requiresReason(), command.requiresNextActivity(),
            command.active(), command.sortOrder());
        return new Outcome(code, label, command.requiresReason(), command.requiresNextActivity(),
            command.active(), command.sortOrder());
    }

    @Override
    @Transactional
    public Outcome requireActive(String rawCode) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        ensureDefaults(organizationId);
        String code = normalizeCode(rawCode);
        Record row = dsl.fetchOne("""
            SELECT code, label, requires_reason, requires_next_activity, active, sort_order
            FROM contact_outcome_definition
            WHERE organization_id = ? AND code = ? AND active = TRUE
            """, organizationId, code);
        if (row == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Valitud kontakti tulemus ei ole aktiivne.");
        }
        return map(row);
    }

    private void ensureDefaults(UUID organizationId) {
        Object[][] defaults = {
            {"CONTACTED", "Kontakt loodud", false, false, 10},
            {"NO_ANSWER", "Ei vastanud", false, true, 20},
            {"INTERESTED", "Huvitatud", false, false, 30},
            {"NOT_INTERESTED", "Ei ole huvitatud", true, false, 40},
            {"FOLLOW_UP", "Vajab järeltegevust", false, true, 50}
        };
        for (Object[] value : defaults) {
            dsl.execute("""
                INSERT INTO contact_outcome_definition
                    (organization_id, code, label, requires_reason, requires_next_activity, active, sort_order)
                VALUES (?, ?, ?, ?, ?, TRUE, ?)
                ON CONFLICT DO NOTHING
                """, organizationId, value[0], value[1], value[2], value[3], value[4]);
        }
    }

    private Outcome map(Record row) {
        return new Outcome(
            row.get("code", String.class),
            row.get("label", String.class),
            Boolean.TRUE.equals(row.get("requires_reason", Boolean.class)),
            Boolean.TRUE.equals(row.get("requires_next_activity", Boolean.class)),
            Boolean.TRUE.equals(row.get("active", Boolean.class)),
            row.get("sort_order", Integer.class));
    }

    private static String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]+")) {
            throw new ResponseStatusException(BAD_REQUEST, "Tulemuse kood on vigane.");
        }
        return normalized;
    }
}
