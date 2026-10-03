package ee.forestiq.admin;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    private final DSLContext dsl;

    public DashboardRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public DashboardStats getStats(UUID orgId) {
        var ownerMetrics = dsl.fetchOne("""
            SELECT COUNT(*) AS active_owners,
                   COUNT(*) FILTER (WHERE status = 'NEW') AS new_leads,
                   COUNT(*) FILTER (WHERE status IN ('INTERESTED', 'CONTACTED')) AS active_opportunities,
                   COUNT(*) FILTER (WHERE status IN ('EVALUATION', 'WAITS_FOR_EVALUATION')) AS pending_evaluation,
                   COUNT(*) FILTER (WHERE status = 'ASSIGNED') AS assigned_work,
                   COUNT(*) FILTER (WHERE status = 'CONTRACTED') AS contracted
            FROM owner WHERE organization_id = ?
            """, orgId);
        long totalParcels = dsl.selectCount()
            .from(table("land_parcel"))
            .where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("boundary")).isNotNull()))
            .fetchOne(0, long.class);
        long overdueReminders = dsl.selectCount()
            .from(table("reminder"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("due_time"), Instant.class).lessThan(Instant.now())))
            .fetchOne(0, long.class);

        return new DashboardStats(
            ownerMetrics.get("active_owners", Long.class), ownerMetrics.get("new_leads", Long.class),
            ownerMetrics.get("active_opportunities", Long.class), ownerMetrics.get("pending_evaluation", Long.class),
            ownerMetrics.get("assigned_work", Long.class), ownerMetrics.get("contracted", Long.class),
            totalParcels, overdueReminders);
    }
}
