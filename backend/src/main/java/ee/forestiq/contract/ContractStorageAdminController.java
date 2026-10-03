package ee.forestiq.contract;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ee.forestiq.contract.ContractStorageReconciliationService.ReconciliationResult;
import ee.forestiq.security.OrganizationContext;

@RestController
@RequestMapping("/api/v1/contracts/storage")
public class ContractStorageAdminController {

    private final OrganizationContext organizationContext;
    private final ContractStorageReconciliationService reconciliationService;

    public ContractStorageAdminController(OrganizationContext organizationContext,
                                          ContractStorageReconciliationService reconciliationService) {
        this.organizationContext = organizationContext;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ReconciliationResult reconcile(@RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "100") int limit) {
        return reconciliationService.reconcile(organizationContext.requiredOrganizationId(), offset, limit);
    }
}
