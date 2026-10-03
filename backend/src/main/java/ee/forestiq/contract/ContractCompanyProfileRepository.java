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

import ee.forestiq.contract.ContractConfigurationService.CompanyProfile;
import ee.forestiq.contract.ContractConfigurationService.SaveCompanyRequest;

@Repository
class ContractCompanyProfileRepository {

    private final DSLContext dsl;

    ContractCompanyProfileRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    List<CompanyProfile> list(UUID orgId) {
        return selectCompany()
            .from(table("company_profile"))
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .orderBy(field(name("is_default")).desc(), field(name("legal_name")).asc())
            .fetch(CompanyProfile::from);
    }

    Optional<CompanyProfile> findById(UUID orgId, UUID id) {
        return selectCompany()
            .from(table("company_profile"))
            .where(field(name("id"), UUID.class).eq(id).and(field(name("organization_id"), UUID.class).eq(orgId)))
            .fetchOptional(CompanyProfile::from);
    }

    Optional<CompanyProfile> findDefault(UUID orgId) {
        return selectCompany()
            .from(table("company_profile"))
            .where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("is_default"), Boolean.class).eq(true)))
            .fetchOptional(CompanyProfile::from);
    }

    void clearDefault(UUID orgId) {
        dsl.update(table("company_profile"))
            .set(field(name("is_default")), false)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
    }

    void insert(UUID orgId, UUID companyId, SaveCompanyRequest request) {
        dsl.insertInto(table("company_profile"))
            .columns(field(name("id")), field(name("organization_id")), field(name("legal_name")), field(name("registry_code")),
                field(name("vat_code")), field(name("address")), field(name("email")), field(name("phone")), field(name("website")),
                field(name("bank_name")), field(name("iban")), field(name("bic")), field(name("representative_name")),
                field(name("representative_role")), field(name("is_default")))
            .values(companyId, orgId, request.legalName(), request.registryCode(), request.vatCode(), request.address(), request.email(),
                request.phone(), request.website(), request.bankName(), request.iban(), request.bic(), request.representativeName(),
                request.representativeRole(), Boolean.TRUE.equals(request.isDefault()))
            .execute();
    }

    int update(UUID orgId, UUID companyId, SaveCompanyRequest request) {
        return dsl.update(table("company_profile"))
            .set(field(name("legal_name")), request.legalName()).set(field(name("registry_code")), request.registryCode())
            .set(field(name("vat_code")), request.vatCode()).set(field(name("address")), request.address())
            .set(field(name("email")), request.email()).set(field(name("phone")), request.phone()).set(field(name("website")), request.website())
            .set(field(name("bank_name")), request.bankName()).set(field(name("iban")), request.iban()).set(field(name("bic")), request.bic())
            .set(field(name("representative_name")), request.representativeName()).set(field(name("representative_role")), request.representativeRole())
            .set(field(name("is_default")), Boolean.TRUE.equals(request.isDefault())).set(field(name("updated_at")), Instant.now())
            .where(field(name("id"), UUID.class).eq(companyId).and(field(name("organization_id"), UUID.class).eq(orgId)))
            .execute();
    }

    private org.jooq.SelectSelectStep<org.jooq.Record15<UUID, String, String, String, String, String, String, String, String, String, String, String, String, String, Boolean>> selectCompany() {
        return dsl.select(
            field(name("id"), UUID.class), field(name("legal_name"), String.class), field(name("registry_code"), String.class),
            field(name("vat_code"), String.class), field(name("address"), String.class), field(name("email"), String.class),
            field(name("phone"), String.class), field(name("website"), String.class), field(name("bank_name"), String.class),
            field(name("iban"), String.class), field(name("bic"), String.class), field(name("representative_name"), String.class),
            field(name("representative_role"), String.class), field(name("logo_object_key"), String.class), field(name("is_default"), Boolean.class)
        );
    }
}
