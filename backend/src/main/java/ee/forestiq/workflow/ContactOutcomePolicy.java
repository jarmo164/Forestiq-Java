package ee.forestiq.workflow;

import java.util.List;

/** Workflow-owned policy boundary for organization-specific contact outcomes. */
public interface ContactOutcomePolicy {

    List<Outcome> list();

    Outcome update(String rawCode, Update command);

    Outcome requireActive(String rawCode);

    record Outcome(
        String code,
        String label,
        boolean requiresReason,
        boolean requiresNextActivity,
        boolean active,
        int sortOrder) {
    }

    record Update(
        String label,
        boolean requiresReason,
        boolean requiresNextActivity,
        boolean active,
        int sortOrder) {
    }
}
