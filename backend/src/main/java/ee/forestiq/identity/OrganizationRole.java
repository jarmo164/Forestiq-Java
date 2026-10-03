package ee.forestiq.identity;

import java.util.Locale;

/** ForestIQ application roles independent of the identity-provider representation. */
public enum OrganizationRole {
    ORG_ADMIN,
    CRM_MANAGER,
    EVALUATOR,
    CALLER,
    VIEWER;

    public static OrganizationRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Organization role is required");
        }
        return OrganizationRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
