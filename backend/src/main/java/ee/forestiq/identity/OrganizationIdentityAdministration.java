package ee.forestiq.identity;

import java.util.Set;
import java.util.UUID;

/** Provider-neutral commands for the existing organization-user administration surface. */
public interface OrganizationIdentityAdministration {
    OrganizationUser createUser(UUID organizationId, CreateOrganizationUser command);

    void updateRoles(UUID organizationId, String subject, Set<OrganizationRole> roles, String currentSubject);

    void deleteUser(UUID organizationId, String subject, String currentSubject);

    record CreateOrganizationUser(String username, String displayName, String temporaryPassword,
                                  Set<OrganizationRole> roles) {
        public CreateOrganizationUser {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }
}
