package ee.forestiq.admin;

/** Stable dashboard read model shared by the application and HTTP adapters. */
public record DashboardStats(
    long activeOwners,
    long newLeads,
    long activeOpportunities,
    long pendingEvaluation,
    long assignedWork,
    long contracted,
    long totalParcels,
    long overdueReminders
) { }
