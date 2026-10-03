package ee.forestiq.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application-facing query and eligibility boundary for active organization users.
 * Implementations may hide provider cache and transport details, but consumers must
 * never depend on identity-provider DTOs, HTTP clients, or token management.
 */
public interface OrganizationIdentityDirectory {
    List<OrganizationUser> listActiveOrganizationUsers(UUID organizationId);

    OrganizationUser requireActiveOrganizationUser(UUID organizationId, String subject);

    OrganizationUser requireAssignableUser(UUID organizationId, String subject, Set<OrganizationRole> allowedRoles);
}
