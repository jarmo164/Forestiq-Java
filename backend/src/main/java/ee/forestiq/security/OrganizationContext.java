package ee.forestiq.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class OrganizationContext {

    public UUID requiredOrganizationId() {
        return organizationId().orElseThrow(() -> new AccessDeniedException("JWT does not contain an organization_id claim"));
    }

    public Optional<UUID> organizationId() {
        return TenantDatabaseContext.currentOrganizationId();
    }

    public String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("An authenticated subject is required");
        }
        return jwt.getSubject();
    }
}
