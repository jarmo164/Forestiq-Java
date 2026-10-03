package ee.forestiq.admin;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.StatusChangeFrame;
import ee.forestiq.owner.OwnerDtos.UserStatusChangeStats;

@Repository
public class AdminUserStatisticsRepository {

    private final DSLContext dsl;

    public AdminUserStatisticsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<UserStatusChangeStats> getUserStatistics(
            UUID orgId,
            List<String> userIds,
            List<String> fromStatuses,
            List<String> toStatuses,
            String granularity,
            Instant from,
            Instant to) {
        return userIds.stream().map(userId -> {
            Condition condition = field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("user_subject"), String.class).eq(userId))
                .and(field(name("created_at"), Instant.class).greaterOrEqual(from))
                .and(field(name("created_at"), Instant.class).lessOrEqual(to));
            if (fromStatuses != null && !fromStatuses.isEmpty()) {
                condition = condition.and(field(name("from_status"), String.class).in(fromStatuses));
            }
            if (toStatuses != null && !toStatuses.isEmpty()) {
                condition = condition.and(field(name("to_status"), String.class).in(toStatuses));
            }
            var frames = dsl.select(
                    field("date_trunc({0}, {1})", Instant.class, val(granularity), field(name("created_at"))).as("frame_start"),
                    count().as("cnt"))
                .from(table("user_status_change_record"))
                .where(condition)
                .groupBy(field("frame_start"))
                .orderBy(field("frame_start"))
                .fetch(r -> new StatusChangeFrame(r.get("frame_start", Instant.class), r.get("cnt", Long.class)));
            return new UserStatusChangeStats(userId, frames);
        }).toList();
    }
}
