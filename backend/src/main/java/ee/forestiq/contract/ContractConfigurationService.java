package ee.forestiq.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

@Service
public class ContractConfigurationService {

    public static final List<String> TEMPLATE_PLACEHOLDERS = List.of(
        "{{contract.number}}", "{{contract.date}}", "{{contract.place}}", "{{contract.price}}", "{{contract.terms}}", "{{contract.parcelsRows}}",
        "{{seller.name}}", "{{seller.code}}", "{{seller.address}}", "{{seller.iban}}",
        "{{company.legalName}}", "{{company.registryCode}}", "{{company.address}}", "{{company.email}}", "{{company.phone}}",
        "{{company.representativeName}}", "{{company.representativeRole}}"
    );

    private final OrganizationContext organizationContext;
    private final ContractCompanyProfileRepository companyRepository;
    private final ContractTemplateConfigurationRepository templateRepository;

    public ContractConfigurationService(OrganizationContext organizationContext, ContractCompanyProfileRepository companyRepository,
                                        ContractTemplateConfigurationRepository templateRepository) {
        this.organizationContext = organizationContext;
        this.companyRepository = companyRepository;
        this.templateRepository = templateRepository;
    }

    public List<CompanyProfile> listCompanies() {
        UUID orgId = organizationContext.requiredOrganizationId();
        return companyRepository.list(orgId);
    }

    public List<ContractTemplate> listTemplates() {
        UUID orgId = organizationContext.requiredOrganizationId();
        return templateRepository.list(orgId);
    }

    @Transactional
    public CompanyProfile saveCompany(UUID id, SaveCompanyRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        if (request.legalName() == null || request.legalName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ettevõtte juriidiline nimi on kohustuslik");
        }
        UUID companyId = id == null ? UUID.randomUUID() : id;
        if (Boolean.TRUE.equals(request.isDefault())) {
            companyRepository.clearDefault(orgId);
        }
        if (id == null) {
            companyRepository.insert(orgId, companyId, request);
        } else {
            int updated = companyRepository.update(orgId, companyId, request);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ettevõtteprofiili ei leitud");
        }
        return companyById(orgId, companyId);
    }

    @Transactional
    public ContractTemplate saveTemplate(UUID id, SaveTemplateRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        if (blank(request.name()) || blank(request.code()) || blank(request.htmlTemplate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malli nimi, kood ja HTML-sisu on kohustuslikud");
        }
        UUID templateId = id == null ? UUID.randomUUID() : id;
        if (request.companyProfileId() != null) {
            companyById(orgId, request.companyProfileId());
        }
        if (Boolean.TRUE.equals(request.isDefault())) {
            templateRepository.clearDefault(orgId);
        }
        if (id == null) {
            templateRepository.insert(orgId, templateId, request, subject);
        } else {
            int updated = templateRepository.update(orgId, templateId, request, subject);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lepingumalli ei leitud");
        }
        return templateById(orgId, templateId);
    }

    public CompanyProfile resolveCompany(UUID orgId, UUID companyId) {
        if (companyId != null) return companyById(orgId, companyId);
        return companyRepository.findDefault(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vaikimisi ettevõtteprofiil puudub"));
    }

    public ContractTemplate resolveTemplate(UUID orgId, UUID templateId) {
        if (templateId != null) {
            ContractTemplate template = templateById(orgId, templateId);
            if (!template.active()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valitud lepingumall ei ole aktiivne");
            return template;
        }
        return templateRepository.findDefaultActive(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vaikimisi lepingumall puudub"));
    }

    private CompanyProfile companyById(UUID orgId, UUID id) {
        return companyRepository.findById(orgId, id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ettevõtteprofiili ei leitud"));
    }

    private ContractTemplate templateById(UUID orgId, UUID id) {
        return templateRepository.findById(orgId, id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lepingumalli ei leitud"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record CompanyProfile(UUID id, String legalName, String registryCode, String vatCode, String address, String email,
                                 String phone, String website, String bankName, String iban, String bic, String representativeName,
                                 String representativeRole, String logoObjectKey, boolean isDefault) {
        static CompanyProfile from(org.jooq.Record r) { return new CompanyProfile(r.get("id", UUID.class), r.get("legal_name", String.class), r.get("registry_code", String.class), r.get("vat_code", String.class), r.get("address", String.class), r.get("email", String.class), r.get("phone", String.class), r.get("website", String.class), r.get("bank_name", String.class), r.get("iban", String.class), r.get("bic", String.class), r.get("representative_name", String.class), r.get("representative_role", String.class), r.get("logo_object_key", String.class), Boolean.TRUE.equals(r.get("is_default", Boolean.class))); }
    }
    public record ContractTemplate(UUID id, UUID companyProfileId, String name, String code, String description, String documentType,
                                   String htmlTemplate, boolean active, boolean isDefault, Instant updatedAt) {
        static ContractTemplate from(org.jooq.Record r) { return new ContractTemplate(r.get("id", UUID.class), r.get("company_profile_id", UUID.class), r.get("name", String.class), r.get("code", String.class), r.get("description", String.class), r.get("document_type", String.class), r.get("html_template", String.class), Boolean.TRUE.equals(r.get("active", Boolean.class)), Boolean.TRUE.equals(r.get("is_default", Boolean.class)), r.get("updated_at", Instant.class)); }
    }
    public record SaveCompanyRequest(String legalName, String registryCode, String vatCode, String address, String email, String phone,
                                     String website, String bankName, String iban, String bic, String representativeName,
                                     String representativeRole, Boolean isDefault) { }
    public record SaveTemplateRequest(UUID companyProfileId, String name, String code, String description, String htmlTemplate,
                                      Boolean active, Boolean isDefault) { }
}
