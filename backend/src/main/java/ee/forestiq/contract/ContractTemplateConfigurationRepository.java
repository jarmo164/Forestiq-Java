package ee.forestiq.contract;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.contract.ContractConfigurationService.ContractTemplate;
import ee.forestiq.contract.ContractConfigurationService.SaveTemplateRequest;

@Repository
class ContractTemplateConfigurationRepository {

    private final DSLContext dsl;

    ContractTemplateConfigurationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    List<ContractTemplate> list(UUID orgId) {
        return selectTemplate()
            .from(table("contract_template"))
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .orderBy(field(name("is_default")).desc(), field(name("name")).asc())
            .fetch(ContractTemplate::from);
    }

    Optional<ContractTemplate> findById(UUID orgId, UUID id) {
        return selectTemplate()
            .from(table("contract_template"))
            .where(field(name("id"), UUID.class).eq(id).and(field(name("organization_id"), UUID.class).eq(orgId)))
            .fetchOptional(ContractTemplate::from);
    }

    Optional<ContractTemplate> findDefaultActive(UUID orgId) {
        return selectTemplate()
            .from(table("contract_template"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("is_default"), Boolean.class).eq(true))
                .and(field(name("active"), Boolean.class).eq(true)))
            .fetchOptional(ContractTemplate::from);
    }

    void clearDefault(UUID orgId) {
        dsl.update(table("contract_template"))
            .set(field(name("is_default")), false)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
    }

    void insert(UUID orgId, UUID templateId, SaveTemplateRequest request, String subject) {
        dsl.insertInto(table("contract_template"))
            .columns(field(name("id")), field(name("organization_id")), field(name("company_profile_id")), field(name("name")),
                field(name("code")), field(name("description")), field(name("document_type")), field(name("html_template")),
                field(name("active")), field(name("is_default")), field(name("created_by_subject")), field(name("updated_by_subject")))
            .values(templateId, orgId, request.companyProfileId(), request.name(), request.code(), request.description(), "PDF", request.htmlTemplate(),
                !Boolean.FALSE.equals(request.active()), Boolean.TRUE.equals(request.isDefault()), subject, subject)
            .execute();
    }

    int update(UUID orgId, UUID templateId, SaveTemplateRequest request, String subject) {
        return dsl.update(table("contract_template"))
            .set(field(name("company_profile_id")), request.companyProfileId()).set(field(name("name")), request.name())
            .set(field(name("code")), request.code()).set(field(name("description")), request.description()).set(field(name("html_template")), request.htmlTemplate())
            .set(field(name("active")), !Boolean.FALSE.equals(request.active())).set(field(name("is_default")), Boolean.TRUE.equals(request.isDefault()))
            .set(field(name("updated_by_subject")), subject).set(field(name("updated_at")), Instant.now())
            .where(field(name("id"), UUID.class).eq(templateId).and(field(name("organization_id"), UUID.class).eq(orgId)))
            .execute();
    }

    private org.jooq.SelectSelectStep<org.jooq.Record10<UUID, UUID, String, String, String, String, String, Boolean, Boolean, Instant>> selectTemplate() {
        return dsl.select(
            field(name("id"), UUID.class), field(name("company_profile_id"), UUID.class), field(name("name"), String.class),
            field(name("code"), String.class), field(name("description"), String.class), field(name("document_type"), String.class),
            field(name("html_template"), String.class), field(name("active"), Boolean.class), field(name("is_default"), Boolean.class),
            field(name("updated_at"), Instant.class)
        );
    }
}
