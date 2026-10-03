package ee.forestiq.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class OrganizationContextTest {

    private final OrganizationContext organizationContext = new OrganizationContext();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsOrganizationIdAndSubjectFromJwtPrincipal() {
        UUID organizationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            jwt(Map.of("sub", "user-subject", "organization_id", organizationId.toString())),
            null
        ));

        assertThat(organizationContext.organizationId()).contains(organizationId);
        assertThat(organizationContext.requiredOrganizationId()).isEqualTo(organizationId);
        assertThat(organizationContext.subject()).isEqualTo("user-subject");
    }

    @Test
    void explicitDatabaseScopeCanSupplyOrganizationForBackgroundWork() {
        UUID organizationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        UUID resolved = TenantDatabaseContext.callWithOrganization(
            organizationId,
            organizationContext::requiredOrganizationId
        );

        assertThat(resolved).isEqualTo(organizationId);
        assertThat(organizationContext.organizationId()).isEmpty();
    }

    @Test
    void rejectsMissingOrganizationClaimWhenRequired() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            jwt(Map.of("sub", "user-subject")),
            null
        ));

        assertThat(organizationContext.organizationId()).isEmpty();
        assertThatThrownBy(organizationContext::requiredOrganizationId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsInvalidOrganizationClaimWhenRequired() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            jwt(Map.of("sub", "user-subject", "organization_id", "not-a-uuid")),
            null
        ));

        assertThat(organizationContext.organizationId()).isEmpty();
        assertThatThrownBy(organizationContext::requiredOrganizationId).isInstanceOf(AccessDeniedException.class);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
