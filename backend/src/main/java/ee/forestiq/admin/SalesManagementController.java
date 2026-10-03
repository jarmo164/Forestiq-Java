package ee.forestiq.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.security.OrganizationContext;

/**
 * Management view for a small sales organization. It deliberately reports
 * operational signals that lead to an action rather than vanity totals.
 */
@RestController
@RequestMapping("/api/v1/sales-management")
@Tag(name = "Sales management", description = "Team workload, activity and commercial intervention priorities")
public class SalesManagementController {

    private final SalesManagementRepository salesManagementRepository;
    private final OrganizationContext organizationContext;

    public SalesManagementController(SalesManagementRepository salesManagementRepository, OrganizationContext organizationContext) {
        this.salesManagementRepository = salesManagementRepository;
        this.organizationContext = organizationContext;
    }

    @GetMapping("/overview")
    @Operation(summary = "Get team management overview and intervention queue")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public SalesManagementOverview overview() {
        return salesManagementRepository.overview(organizationContext.requiredOrganizationId());
    }

    public record SalesManagementOverview(
        long totalOwners, long assignedOwners, long unassignedLeads, long staleOpportunities,
        long overdueReminders, long salesActivitiesLast7Days,
        List<TeamMemberPerformance> team, List<InterventionItem> interventions
    ) { }

    public record TeamMemberPerformance(
        String subject, long assignedOwners, long activeOpportunities, long statusChangesLast7Days,
        long overdueReminders, Instant lastActivityAt
    ) { }

    public record InterventionItem(
        UUID ownerId, String ownerName, String status, String assignedTo, Instant updatedAt, String reason
    ) { }
}
