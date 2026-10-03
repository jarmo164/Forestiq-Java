package ee.forestiq.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ee.forestiq.support.H2JooqTestDatabase;

class ContractRepositoryIsolationTest {
    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTRACT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private ContractRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("contract-isolation-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table contract (
                id uuid primary key,
                organization_id uuid not null,
                contract_data varbinary,
                object_key varchar(512),
                file_name varchar(512)
            )
            """);
        repository = new ContractRepository(dsl, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void contractFileAndObjectKeyCannotCrossOrganizationBoundary() {
        byte[] pdf = "%PDF-org-b".getBytes(StandardCharsets.UTF_8);
        dsl.execute("""
            insert into contract (id, organization_id, contract_data, object_key, file_name)
            values (?, ?, ?, 'contracts/org-b.pdf', 'org-b.pdf')
            """, CONTRACT_ID, ORG_B, pdf);

        assertThat(repository.findContractFile(ORG_A, CONTRACT_ID)).isEmpty();

        repository.updateObjectKey(ORG_A, CONTRACT_ID, "contracts/forged-org-a.pdf");

        String persistedObjectKey = dsl.fetchOne(
            "select object_key from contract where id = ?", CONTRACT_ID).get(0, String.class);
        assertThat(persistedObjectKey).isEqualTo("contracts/org-b.pdf");
        assertThat(repository.findContractFile(ORG_B, CONTRACT_ID)).isPresent();
    }
}
