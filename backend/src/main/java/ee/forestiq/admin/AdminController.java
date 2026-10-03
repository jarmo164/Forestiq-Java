package ee.forestiq.admin;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.admin.AdminUserAssignmentGuard.UserDeletionImpact;
import ee.forestiq.identity.OrganizationIdentityAdministration;
import ee.forestiq.identity.OrganizationIdentityAdministration.CreateOrganizationUser;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.identity.OrganizationUser;
import ee.forestiq.owner.OwnerDtos.*;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Admin workdesk, user management, owner status definitions, statistics, and mass operations")
public class AdminController {

    private static final Set<OrganizationRole> OWNER_ASSIGNMENT_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);

    private final OwnerService ownerService;
    private final AdminUserStatisticsService userStatisticsService;
    private final OrganizationContext orgCtx;
    private final OrganizationIdentityDirectory identityDirectory;
    private final OrganizationIdentityAdministration identityAdministration;
    private final AdminUserAssignmentGuard userAssignmentGuard;

    public AdminController(OwnerService ownerService, AdminUserStatisticsService userStatisticsService,
                           OrganizationContext orgCtx, OrganizationIdentityDirectory identityDirectory,
                           OrganizationIdentityAdministration identityAdministration,
                           AdminUserAssignmentGuard userAssignmentGuard) {
        this.ownerService = ownerService;
        this.userStatisticsService = userStatisticsService;
        this.orgCtx = orgCtx;
        this.identityDirectory = identityDirectory;
        this.identityAdministration = identityAdministration;
        this.userAssignmentGuard = userAssignmentGuard;
    }

    @PostMapping("/workdesk/search")
    @Operation(summary = "Admin workdesk search with complex criteria")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<OwnerListItem> adminSearch(@Valid @RequestBody AdminSearchRequest request) {
        return ownerService.adminSearch(request);
    }

    @GetMapping("/workdesk/prep-data")
    @Operation(summary = "Get admin workdesk preparation data")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public AdminPrepData getPrepData() {
        AdminPrepData base = ownerService.getAdminPrepData();
        List<UserMinimal> callers = identityDirectory.listActiveOrganizationUsers(orgCtx.requiredOrganizationId()).stream()
            .filter(user -> user.roles().stream().anyMatch(OWNER_ASSIGNMENT_ROLES::contains))
            .map(user -> new UserMinimal(user.subject(), user.displayName()))
            .toList();
        return new AdminPrepData(base.counties(), base.municipalities(), callers, base.ownerTypes(), base.statuses());
    }

    @PostMapping("/workdesk/assign")
    @Operation(summary = "Mass assign owners to an active organization caller")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignWork(@Valid @RequestBody AssignWorkRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        identityDirectory.requireAssignableUser(orgId, request.assignee(), OWNER_ASSIGNMENT_ROLES);
        ownerService.assignWork(request);
    }

    @GetMapping("/owner-statuses")
    @Operation(summary = "List all owner status definitions")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<OwnerStatusDefinition> getStatusDefinitions() {
        return ownerService.getStatusDefinitions();
    }

    @PostMapping("/owner-statuses")
    @Operation(summary = "Create or update owner status definition")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveStatusDefinition(@Valid @RequestBody SaveOwnerStatusRequest request) {
        ownerService.saveStatusDefinition(request);
    }

    @DeleteMapping("/owner-statuses/{id}")
    @Operation(summary = "Delete owner status definition")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatusDefinition(@PathVariable String id) {
        ownerService.deleteStatusDefinition(id);
    }

    @GetMapping("/users")
    @Operation(summary = "List all active users in the active organization")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public List<MaintainableUserDto> getUsers() {
        return identityDirectory.listActiveOrganizationUsers(orgCtx.requiredOrganizationId()).stream()
            .map(AdminController::maintainableUser)
            .toList();
    }

    @PostMapping("/users")
    @Operation(summary = "Create an organization user in Keycloak")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintainableUserDto createUser(@Valid @RequestBody CreateUserRequest request) {
        OrganizationUser user = identityAdministration.createUser(orgCtx.requiredOrganizationId(),
            new CreateOrganizationUser(request.username(), request.name(), request.password(), parseRoles(request.roles())));
        return maintainableUser(user);
    }

    @PutMapping("/users/{userId}/roles")
    @Operation(summary = "Update Keycloak realm roles for an organization user")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateUserRoles(@PathVariable String userId, @Valid @RequestBody UpdateRolesRequest request) {
        identityAdministration.updateRoles(orgCtx.requiredOrganizationId(), userId, parseRoles(request.roles()), orgCtx.subject());
    }

    @GetMapping("/users/{userId}/deletion-impact")
    @Operation(summary = "Preview active ForestIQ work that must be reassigned before deleting a user")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public UserDeletionImpact userDeletionImpact(@PathVariable String userId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        identityDirectory.requireActiveOrganizationUser(orgId, userId);
        return userAssignmentGuard.impact(orgId, userId);
    }

    @DeleteMapping("/users/{userId}")
    @Operation(summary = "Delete an organization user in Keycloak after active work has been reassigned")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String userId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        identityDirectory.requireActiveOrganizationUser(orgId, userId);
        userAssignmentGuard.requireSafeToDelete(orgId, userId);
        identityAdministration.deleteUser(orgId, userId, orgCtx.subject());
    }

    @PostMapping("/user-statistics")
    @Operation(summary = "Get user status change statistics")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<UserStatusChangeStats> getUserStatistics(@Valid @RequestBody UserStatisticsRequest request) {
        return userStatisticsService.getUserStatistics(
            request.userIds(), request.fromStatuses(), request.toStatuses(),
            request.granularity(), request.from(), request.to());
    }

    private static MaintainableUserDto maintainableUser(OrganizationUser user) {
        return new MaintainableUserDto(user.subject(), user.username(), user.displayName(),
            user.roles().stream().map(OrganizationRole::name).sorted().toList());
    }

    private static Set<OrganizationRole> parseRoles(List<String> roleNames) {
        try {
            return roleNames == null ? Set.of() : roleNames.stream().map(OrganizationRole::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Taotlus sisaldab tundmatut ForestIQ rolli.", exception);
        }
    }

    public record MaintainableUserDto(String id, String username, String name, List<String> roles) {}
    public record CreateUserRequest(
        @NotBlank @Size(max = 200) String username,
        @NotBlank @Size(max = 300) String name,
        @NotBlank @Size(min = 12, max = 200) String password,
        @NotEmpty List<@NotBlank String> roles
    ) {}
    public record UpdateRolesRequest(@NotEmpty List<@NotBlank String> roles) {}
    public record UserStatisticsRequest(
        @NotEmpty List<@NotBlank String> userIds,
        List<@NotBlank String> fromStatuses,
        List<@NotBlank String> toStatuses,
        @NotBlank @Pattern(regexp = "hour|day|week|month") String granularity,
        @NotNull Instant from,
        @NotNull Instant to
    ) {}
}
