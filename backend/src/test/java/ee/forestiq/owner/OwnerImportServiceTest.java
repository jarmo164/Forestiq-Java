package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.OUTBOX_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerImportService.ImportMapping;
import ee.forestiq.owner.OwnerImportService.RowStatus;
import ee.forestiq.security.OrganizationContext;

class OwnerImportServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXISTING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Connection connection;
    private DSLContext dsl;
    private OwnerRepository ownerRepository;
    private OwnerImportService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:owner-import-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "sa", "");
        dsl = DSL.using(connection, SQLDialect.H2);
        createSchema();

        ownerRepository = mock(OwnerRepository.class);
        OrganizationContext context = mock(OrganizationContext.class);
        when(context.requiredOrganizationId()).thenReturn(ORG_ID);
        when(context.subject()).thenReturn("manager-subject");

        service = new OwnerImportService(
            new OwnerImportFileParser(),
            ownerRepository,
            new OwnerActivityRepository(dsl),
            new OwnerFollowingRepository(dsl),
            context,
            dsl,
            new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void previewReportsExistingDuplicateAndRepeatedFileCodeWithoutWrites() {
        Owner existing = new Owner(EXISTING_ID, ORG_ID, "Olemas", "12345678", null, null, OwnerStatus.NEW, null);
        when(ownerRepository.findByOrganizationIdAndPersonalOrRegistryCodeIn(eq(ORG_ID), anyCollection()))
            .thenReturn(List.of(existing));

        var file = csv("""
            Nimi;Kood;E-post
            Olemas;12345678;old@example.test
            Uus A;87654321;a@example.test
            Uus B;87654321;b@example.test
            Vigane;;broken
            """);
        ImportMapping mapping = new ImportMapping(Map.of(
            "displayName", "Nimi",
            "personalOrRegistryCode", "Kood",
            "email", "E-post"));

        var preview = service.preview(file, mapping);

        assertThat(preview.totalRows()).isEqualTo(4);
        assertThat(preview.readyRows()).isZero();
        assertThat(preview.duplicateRows()).isEqualTo(1);
        assertThat(preview.invalidRows()).isEqualTo(3);
        assertThat(preview.rows().get(0).status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(preview.rows().get(0).existingOwnerId()).isEqualTo(EXISTING_ID);
        assertThat(preview.rows().get(1).errors()).anyMatch(message -> message.contains("failis mitu korda"));
        assertThat(preview.rows().get(3).errors()).anyMatch(message -> message.contains("kohustuslik"));
    }

    @Test
    void organizationColumnCannotBeMappedAsAuthority() {
        var file = csv("""
            Nimi;Kood;organizationId
            Uus Omanik;87654321;99999999-9999-9999-9999-999999999999
            """);
        ImportMapping mapping = new ImportMapping(Map.of(
            "displayName", "Nimi",
            "personalOrRegistryCode", "Kood",
            "organizationId", "organizationId"));

        assertThatThrownBy(() -> service.preview(file, mapping))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Tundmatu omaniku väli mappingus: organizationId");
    }

    @Test
    void commitRequiresPreviewHashAndStoresAuditableBatch() {
        when(ownerRepository.findByOrganizationIdAndPersonalOrRegistryCodeIn(eq(ORG_ID), anyCollection()))
            .thenReturn(List.of());
        when(ownerRepository.saveAll(org.mockito.ArgumentMatchers.<Owner>anyList()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var file = csv("""
            Nimi;Kood;Telefon
            Uus Omanik;87654321;+3725550000
            """);
        ImportMapping mapping = new ImportMapping(Map.of(
            "displayName", "Nimi",
            "personalOrRegistryCode", "Kood",
            "phone", "Telefon"));
        var preview = service.preview(file, mapping);

        assertThatThrownBy(() -> service.commit(file, mapping, "wrong-hash"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("eelvaates kinnitatud failist");

        var committed = service.commit(file, mapping, preview.sha256());

        assertThat(committed.createdCount()).isEqualTo(1);
        assertThat(committed.createdOwnerIds()).hasSize(1);
        verify(ownerRepository).flush();
        assertThat(dsl.fetchCount(OUTBOX_EVENT)).isEqualTo(1);
        assertThat(dsl.fetchCount(ee.forestiq.jooq.Tables.OWNER_ACTIVITY)).isEqualTo(1);
        assertThat(dsl.fetchCount(ee.forestiq.jooq.Tables.OWNER_FOLLOWING)).isEqualTo(1);
        assertThat(service.recentBatches()).singleElement().satisfies(batch -> {
            assertThat(batch.batchId()).isEqualTo(committed.batchId());
            assertThat(batch.createdCount()).isEqualTo(1);
            assertThat(batch.userSubject()).isEqualTo("manager-subject");
        });
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table owner_activity (
              id uuid primary key,
              organization_id uuid not null,
              owner_id uuid not null,
              activity_type varchar(32) not null,
              content text not null,
              created_by_subject varchar(100) not null,
              created_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table owner_following (
              owner_id uuid not null,
              user_subject varchar(100) not null,
              created_at timestamp with time zone default current_timestamp,
              primary key (owner_id, user_subject)
            )
            """);
        dsl.execute("""
            create table outbox_event (
              id uuid primary key,
              organization_id uuid not null,
              aggregate_type varchar(64) not null,
              aggregate_id uuid not null,
              event_type varchar(120) not null,
              payload jsonb not null,
              created_at timestamp with time zone default current_timestamp,
              published_at timestamp with time zone
            )
            """);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "owners.csv", "text/csv",
            content.stripIndent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
