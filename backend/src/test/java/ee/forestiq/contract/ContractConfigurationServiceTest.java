package ee.forestiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.contract.ContractConfigurationService.SaveCompanyRequest;
import ee.forestiq.contract.ContractConfigurationService.SaveTemplateRequest;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;

@ExtendWith(MockitoExtension.class)
class ContractConfigurationServiceTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID COMPANY_A = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID COMPANY_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TEMPLATE_A = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID TEMPLATE_B = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock private OrganizationContext organizationContext;

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private ContractConfigurationService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("contracts-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table company_profile (
                id uuid primary key,
                organization_id uuid not null,
                legal_name varchar(300) not null,
                registry_code varchar(32),
                vat_code varchar(32),
                address varchar(500),
                email varchar(320),
                phone varchar(64),
                website varchar(512),
                bank_name varchar(180),
                iban varchar(64),
                bic varchar(32),
                representative_name varchar(300),
                representative_role varchar(180),
                logo_object_key varchar(512),
                is_default boolean not null default false,
                updated_at timestamp with time zone not null default current_timestamp
            )
            """);
        dsl.execute("""
            create table contract_template (
                id uuid primary key,
                organization_id uuid not null,
                company_profile_id uuid,
                name varchar(240) not null,
                code varchar(100) not null,
                description varchar(500),
                document_type varchar(16) not null default 'PDF',
                html_template varchar(4000) not null,
                active boolean not null default true,
                is_default boolean not null default false,
                created_by_subject varchar(100),
                updated_by_subject varchar(100),
                updated_at timestamp with time zone not null default current_timestamp
            )
            """);
        service = new ContractConfigurationService(
            organizationContext,
            new ContractCompanyProfileRepository(dsl),
            new ContractTemplateConfigurationRepository(dsl)
        );
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void listCompaniesReturnsOnlyAuthenticatedOrganizationCompanies() {
        insertCompany(COMPANY_A, ORG_A, "ForestIQ Demo OU", true);
        insertCompany(COMPANY_B, ORG_B, "Other Tenant OU", true);

        var companies = service.listCompanies();

        assertThat(companies).extracting(ContractConfigurationService.CompanyProfile::id).containsExactly(COMPANY_A);
    }

    @Test
    void listTemplatesReturnsOnlyAuthenticatedOrganizationTemplates() {
        insertCompany(COMPANY_A, ORG_A, "ForestIQ Demo OU", true);
        insertCompany(COMPANY_B, ORG_B, "Other Tenant OU", true);
        insertTemplate(TEMPLATE_A, ORG_A, COMPANY_A, "A template", "A_TEMPLATE", true);
        insertTemplate(TEMPLATE_B, ORG_B, COMPANY_B, "B template", "B_TEMPLATE", true);

        var templates = service.listTemplates();

        assertThat(templates).extracting(ContractConfigurationService.ContractTemplate::id).containsExactly(TEMPLATE_A);
    }

    @Test
    void saveTemplateRejectsCompanyFromAnotherOrganization() {
        insertCompany(COMPANY_B, ORG_B, "Other Tenant OU", true);
        when(organizationContext.subject()).thenReturn("admin-subject");

        assertThatThrownBy(() -> service.saveTemplate(null,
            new SaveTemplateRequest(COMPANY_B, "Cross tenant", "CROSS", null, "<html></html>", true, false)))
            .isInstanceOf(ResponseStatusException.class);

        assertThat(templateCount()).isZero();
    }

    @Test
    void updateCompanyCannotModifyAnotherOrganizationCompany() {
        insertCompany(COMPANY_B, ORG_B, "Other Tenant OU", true);

        assertThatThrownBy(() -> service.saveCompany(COMPANY_B,
            new SaveCompanyRequest("Changed", null, null, null, null, null, null, null, null, null, null, null, false)))
            .isInstanceOf(ResponseStatusException.class);

        assertThat(dsl.select(field(name("legal_name"), String.class)).from(table("company_profile"))
            .where(field(name("id"), UUID.class).eq(COMPANY_B)).fetchOneInto(String.class)).isEqualTo("Other Tenant OU");
    }

    private void insertCompany(UUID companyId, UUID organizationId, String legalName, boolean isDefault) {
        dsl.insertInto(table("company_profile"))
            .columns(field(name("id")), field(name("organization_id")), field(name("legal_name")), field(name("is_default")))
            .values(companyId, organizationId, legalName, isDefault)
            .execute();
    }

    private void insertTemplate(UUID templateId, UUID organizationId, UUID companyId, String name, String code, boolean isDefault) {
        dsl.insertInto(table("contract_template"))
            .columns(field(name("id")), field(name("organization_id")), field(name("company_profile_id")),
                field(name("name")), field(name("code")), field(name("html_template")), field(name("active")),
                field(name("is_default")), field(name("created_by_subject")), field(name("updated_by_subject")))
            .values(templateId, organizationId, companyId, name, code, "<html></html>", true, isDefault, "fixture", "fixture")
            .execute();
    }

    private int templateCount() {
        return dsl.select(count()).from(table("contract_template")).fetchOneInto(Integer.class);
    }
}
