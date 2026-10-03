package ee.forestiq.identity;

import java.util.Set;

/**
 * Provider-neutral representation of an active user in one ForestIQ organization.
 * The subject remains the opaque identifier persisted in business records.
 */
public record OrganizationUser(
    String subject,
    String username,
    String displayName,
    Set<OrganizationRole> roles
) {
    public OrganizationUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
