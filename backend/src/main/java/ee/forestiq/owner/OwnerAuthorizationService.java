package ee.forestiq.owner;

import java.util.Collection;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import ee.forestiq.security.OrganizationContext;

@Service
public class OwnerAuthorizationService {

    private final OrganizationContext organizationContext;

    public OwnerAuthorizationService(OrganizationContext organizationContext) {
        this.organizationContext = organizationContext;
    }

    /**
     * Restricts owner-derived reads to the current caller's assignment whenever
     * the token contains CALLER. Only organization-wide management roles may
     * bypass the assignment boundary. VIEWER/EVALUATOR must not accidentally
     * broaden a CALLER token.
     */
    public Optional<String> assignedSubjectRestriction() {
        return isCallerRestricted()
            ? Optional.of(organizationContext.subject())
            : Optional.empty();
    }

    public void assertCanRead(Owner owner) {
        assertCallerOwnsAssignment(owner.getAssignedToSubject());
    }

    public void assertCanMutate(Owner owner) {
        assertCallerOwnsAssignment(owner.getAssignedToSubject());
    }

    public void assertCanReadAssignedSubject(String assignedToSubject) {
        assertCallerOwnsAssignment(assignedToSubject);
    }

    public void assertCanMutateAssignedSubject(String assignedToSubject) {
        assertCallerOwnsAssignment(assignedToSubject);
    }

    public void assertCanReadAnyAssignedSubject(Collection<String> assignedToSubjects) {
        assertCallerOwnsAnyAssignment(assignedToSubjects);
    }

    public void assertCanMutateAnyAssignedSubject(Collection<String> assignedToSubjects) {
        assertCallerOwnsAnyAssignment(assignedToSubjects);
    }

    public void assertCanChangeAssignment() {
        if (!hasOrganizationWideAccess()) {
            throw new AccessDeniedException("Only organization admins and CRM managers can change owner assignment");
        }
    }

    public boolean hasOrganizationWideAccess() {
        return hasAnyRole("ORG_ADMIN", "CRM_MANAGER");
    }

    /**
     * Sensitive owner/CRM fields are intentionally not part of the read-only
     * VIEWER contract. This policy is applied while the backend read model is
     * assembled, so hiding a field in the browser is never the security boundary.
     */
    public boolean canReadSensitiveOwnerFields() {
        return hasAnyRole("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER");
    }

    private boolean isCallerRestricted() {
        return hasRole("CALLER") && !hasOrganizationWideAccess();
    }

    private void assertCallerOwnsAssignment(String assignedToSubject) {
        if (isCallerRestricted() && !organizationContext.subject().equals(assignedToSubject)) {
            throw new AccessDeniedException("Owner is not assigned to the current caller");
        }
    }

    private void assertCallerOwnsAnyAssignment(Collection<String> assignedToSubjects) {
        if (!isCallerRestricted()) return;
        String subject = organizationContext.subject();
        boolean assigned = assignedToSubjects != null && assignedToSubjects.stream().anyMatch(subject::equals);
        if (!assigned) {
            throw new AccessDeniedException("Parcel has no active owner assigned to the current caller");
        }
    }

    private boolean hasAnyRole(String... roles) {
        for (String role : roles) {
            if (hasRole(role)) return true;
        }
        return false;
    }

    private boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        String authority = "ROLE_" + role;
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority::equals);
    }
}