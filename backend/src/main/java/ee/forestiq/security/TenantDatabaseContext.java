package ee.forestiq.security;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the tenant used by database-level row security.
 *
 * HTTP requests resolve the organization from the validated JWT. Background
 * work must use runWithOrganization/callWithOrganization explicitly.
 */
public final class TenantDatabaseContext {

    private static final ThreadLocal<UUID> SCOPED_ORGANIZATION = new ThreadLocal<>();

    private TenantDatabaseContext() {
    }

    public static Optional<UUID> currentOrganizationId() {
        UUID scoped = SCOPED_ORGANIZATION.get();
        if (scoped != null) {
            return Optional.of(scoped);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        String value = jwt.getClaimAsString("organization_id");
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static void runWithOrganization(UUID organizationId, Runnable action) {
        callWithOrganization(organizationId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithOrganization(UUID organizationId, Supplier<T> action) {
        UUID previous = SCOPED_ORGANIZATION.get();
        SCOPED_ORGANIZATION.set(organizationId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SCOPED_ORGANIZATION.remove();
            } else {
                SCOPED_ORGANIZATION.set(previous);
            }
        }
    }
}
