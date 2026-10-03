package ee.forestiq.contract;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ee.forestiq.contract.ContractConfigurationService.CompanyProfile;
import ee.forestiq.contract.ContractConfigurationService.ContractTemplate;
import ee.forestiq.contract.ContractConfigurationService.SaveCompanyRequest;
import ee.forestiq.contract.ContractConfigurationService.SaveTemplateRequest;

@RestController
@RequestMapping("/api/v1/contract-configuration")
@Tag(name = "Contract configuration", description = "Organisation company profiles and editable PDF contract templates")
public class ContractConfigurationController {

    private final ContractConfigurationService service;

    public ContractConfigurationController(ContractConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/companies")
    @Operation(summary = "List the organisation's company profiles")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<CompanyProfile> listCompanies() { return service.listCompanies(); }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a company profile for contract generation")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public CompanyProfile createCompany(@RequestBody SaveCompanyRequest request) { return service.saveCompany(null, request); }

    @PutMapping("/companies/{id}")
    @Operation(summary = "Update a company profile")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public CompanyProfile updateCompany(@PathVariable UUID id, @RequestBody SaveCompanyRequest request) { return service.saveCompany(id, request); }

    @GetMapping("/templates")
    @Operation(summary = "List editable HTML contract templates")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public List<ContractTemplate> listTemplates() { return service.listTemplates(); }

    @GetMapping("/templates/placeholders")
    @Operation(summary = "List the safe placeholders supported in contract template HTML")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','CRM_MANAGER')")
    public Map<String, List<String>> placeholders() { return Map.of("placeholders", ContractConfigurationService.TEMPLATE_PLACEHOLDERS); }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an editable contract template")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ContractTemplate createTemplate(@RequestBody SaveTemplateRequest request) { return service.saveTemplate(null, request); }

    @PutMapping("/templates/{id}")
    @Operation(summary = "Update an editable contract template")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ContractTemplate updateTemplate(@PathVariable UUID id, @RequestBody SaveTemplateRequest request) { return service.saveTemplate(id, request); }
}
