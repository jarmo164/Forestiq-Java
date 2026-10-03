package ee.forestiq.admin;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.owner.OwnerDtos.UserStatusChangeStats;
import ee.forestiq.security.OrganizationContext;

@Service
public class AdminUserStatisticsService {
    private final AdminUserStatisticsRepository repository;
    private final OrganizationContext organizationContext;

    public AdminUserStatisticsService(AdminUserStatisticsRepository repository,
                                      OrganizationContext organizationContext) {
        this.repository = repository;
        this.organizationContext = organizationContext;
    }

    @Transactional(readOnly = true)
    public List<UserStatusChangeStats> getUserStatistics(
        List<String> userIds,
        List<String> fromStatuses,
        List<String> toStatuses,
        String granularity,
        Instant from,
        Instant to
    ) {
        return repository.getUserStatistics(
            organizationContext.requiredOrganizationId(),
            userIds,
            fromStatuses,
            toStatuses,
            granularity,
            from,
            to);
    }
}
