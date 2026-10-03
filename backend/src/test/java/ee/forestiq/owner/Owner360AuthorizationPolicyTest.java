package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import ee.forestiq.security.OrganizationContext;

class Owner360AuthorizationPolicyTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewerCannotReadSensitiveOwnerFields() {
        authenticate("viewer-subject", "VIEWER");
        OwnerAuthorizationService service = new OwnerAuthorizationService(new StubOrganizationContext());

        assertThat(service.canReadSensitiveOwnerFields()).isFalse();
    }

    @Test
    void evaluatorCanReadSensitiveOwnerFields() {
        authenticate("evaluator-subject", "EVALUATOR");
        OwnerAuthorizationService service = new OwnerAuthorizationService(new StubOrganizationContext());

        assertThat(service.canReadSensitiveOwnerFields()).isTrue();
    }

    @Test
    void callerCanReadSensitiveFieldsButRemainsAssignmentRestricted() {
        authenticate("caller-subject", "CALLER");
        OwnerAuthorizationService service = new OwnerAuthorizationService(new StubOrganizationContext());

        assertThat(service.canReadSensitiveOwnerFields()).isTrue();
        assertThat(service.assignedSubjectRestriction()).contains("caller-subject");
    }

    @Test
    void crmManagerCanReadSensitiveFieldsOrganizationWide() {
        authenticate("manager-subject", "CRM_MANAGER");
        OwnerAuthorizationService service = new OwnerAuthorizationService(new StubOrganizationContext());

        assertThat(service.canReadSensitiveOwnerFields()).isTrue();
        assertThat(service.assignedSubjectRestriction()).isEmpty();
    }

    private static void authenticate(String subject, String... roles) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
            Map.of("sub", subject, "organization_id", ORGANIZATION_ID.toString()));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, "token",
            List.of(roles).stream().map(role -> "ROLE_" + role).toArray(String[]::new)));
    }

    private static final class StubOrganizationContext extends OrganizationContext {
        @Override public UUID requiredOrganizationId() { return ORGANIZATION_ID; }
        @Override public String subject() { return "caller-subject"; }
    }
}