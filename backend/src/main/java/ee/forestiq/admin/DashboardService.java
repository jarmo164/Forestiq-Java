package ee.forestiq.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.security.OrganizationContext;

@Service
public class DashboardService {
    private final DashboardRepository repository;
    private final OrganizationContext organizationContext;

    public DashboardService(DashboardRepository repository, OrganizationContext organizationContext) {
        this.repository = repository;
        this.organizationContext = organizationContext;
    }

    @Transactional(readOnly = true)
    public DashboardStats getStats() {
        return repository.getStats(organizationContext.requiredOrganizationId());
    }
}
