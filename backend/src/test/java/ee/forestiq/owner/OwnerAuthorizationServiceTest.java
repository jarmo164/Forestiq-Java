package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class OwnerAuthorizationServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private OrganizationContext organizationContext;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void callerSearchIsRestrictedToCurrentSubject() {
        authenticate("caller-subject", "CALLER");
        when(organizationContext.subject()).thenReturn("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThat(service.assignedSubjectRestriction()).contains("caller-subject");
    }

    @Test
    void callerCannotReadOwnerAssignedToAnotherSubject() {
        authenticate("caller-subject", "CALLER");
        when(organizationContext.subject()).thenReturn("caller-subject");
        Owner owner = ownerAssignedTo("other-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThatThrownBy(() -> service.assertCanRead(owner))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Owner is not assigned");
    }

    @Test
    void callerCanMutateAssignedOwner() {
        authenticate("caller-subject", "CALLER");
        when(organizationContext.subject()).thenReturn("caller-subject");
        Owner owner = ownerAssignedTo("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThatCode(() -> service.assertCanMutate(owner)).doesNotThrowAnyException();
    }

    @Test
    void callerCanReadCoOwnedParcelWhenAnyActiveOwnerIsAssignedToCaller() {
        authenticate("caller-subject", "CALLER");
        when(organizationContext.subject()).thenReturn("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThatCode(() -> service.assertCanReadAnyAssignedSubject(List.of("other-subject", "caller-subject")))
            .doesNotThrowAnyException();
    }

    @Test
    void callerCannotReadCoOwnedParcelWhenNoActiveOwnerIsAssignedToCaller() {
        authenticate("caller-subject", "CALLER");
        when(organizationContext.subject()).thenReturn("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThatThrownBy(() -> service.assertCanReadAnyAssignedSubject(List.of("other-a", "other-b")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("no active owner assigned");
    }

    @Test
    void viewerRoleDoesNotBroadenCallerAssignmentAccess() {
        authenticate("caller-subject", "CALLER", "VIEWER");
        when(organizationContext.subject()).thenReturn("caller-subject");
        Owner owner = ownerAssignedTo("other-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThat(service.assignedSubjectRestriction()).contains("caller-subject");
        assertThatThrownBy(() -> service.assertCanMutate(owner))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Owner is not assigned");
    }

    @Test
    void evaluatorRoleDoesNotBroadenCallerAssignmentAccess() {
        authenticate("caller-subject", "CALLER", "EVALUATOR");
        when(organizationContext.subject()).thenReturn("caller-subject");
        Owner owner = ownerAssignedTo("other-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThat(service.assignedSubjectRestriction()).contains("caller-subject");
        assertThatThrownBy(() -> service.assertCanRead(owner))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Owner is not assigned");
        assertThatThrownBy(() -> service.assertCanMutate(owner))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Owner is not assigned");
    }

    @Test
    void managerHasOrganizationWideOwnerAccessAndCanChangeAssignment() {
        authenticate("manager-subject", "CRM_MANAGER");
        Owner owner = ownerAssignedTo("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThat(service.assignedSubjectRestriction()).isEmpty();
        assertThat(service.hasOrganizationWideAccess()).isTrue();
        assertThatCode(() -> service.assertCanRead(owner)).doesNotThrowAnyException();
        assertThatCode(service::assertCanChangeAssignment).doesNotThrowAnyException();
    }

    @Test
    void managerRoleMayOverrideCallerRestriction() {
        authenticate("manager-subject", "CALLER", "CRM_MANAGER");
        Owner owner = ownerAssignedTo("caller-subject");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThat(service.assignedSubjectRestriction()).isEmpty();
        assertThatCode(() -> service.assertCanMutate(owner)).doesNotThrowAnyException();
    }

    @Test
    void callerCannotChangeAssignment() {
        authenticate("caller-subject", "CALLER");

        OwnerAuthorizationService service = new OwnerAuthorizationService(organizationContext);

        assertThatThrownBy(service::assertCanChangeAssignment)
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Only organization admins and CRM managers");
    }

    private static Owner ownerAssignedTo(String subject) {
        return new Owner(OWNER_ID, ORGANIZATION_ID, "Mari Mets", "48901010011",
            "mari@example.test", "+37255500101", OwnerStatus.ASSIGNED, subject);
    }

    private static void authenticate(String subject, String... roles) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
            Map.of("sub", subject, "organization_id", ORGANIZATION_ID.toString()));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, "token",
            List.of(roles).stream().map(role -> "ROLE_" + role).toArray(String[]::new)));
    }
}
