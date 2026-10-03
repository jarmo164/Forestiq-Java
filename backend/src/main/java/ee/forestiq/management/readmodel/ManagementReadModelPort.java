package ee.forestiq.management.readmodel;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import ee.forestiq.management.SalesManagementDtos.SegmentRules;

/**
 * Purpose-built read boundary for management projections that combine owner,
 * work-item, registry/cadastre and deal data. Implementations are read-only;
 * management writes remain restricted to management-owned tables and public
 * command ports.
 */
public interface ManagementReadModelPort {

    AssignmentLoad assignmentLoad(UUID organizationId, String subject);

    SegmentSlice segmentMembers(UUID organizationId, SegmentRules rules, int limit);

    record AssignmentLoad(
        long assignedOwners,
        long openWork,
        long overdueWork,
        long dueNextSevenDays,
        long recentlyAssigned) {
    }

    record SegmentRow(
        UUID ownerId,
        String ownerName,
        String status,
        String assignedToSubject,
        BigDecimal forestArea,
        String county,
        boolean hasContact) {
    }

    record SegmentSlice(int total, List<SegmentRow> rows) {
        public SegmentSlice {
            rows = List.copyOf(rows);
        }
    }
}
