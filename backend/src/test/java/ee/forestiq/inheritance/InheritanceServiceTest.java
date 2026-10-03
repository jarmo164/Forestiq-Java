package ee.forestiq.inheritance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;

@ExtendWith(MockitoExtension.class)
class InheritanceServiceTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock private OrganizationContext organizationContext;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private InheritanceOfficialNoticeClient officialNoticeClient;

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private InheritanceService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("inheritance-service-" + UUID.randomUUID());
        dsl = database.dsl();
        createSchema();
        service = new InheritanceService(dsl, organizationContext, eventPublisher, officialNoticeClient);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void startRequiresOwnerToBeConfirmedDeceased() {
        insertOwner(ORG_A, OWNER_ID, OwnerStatus.CONTACTED);

        assertThatThrownBy(() -> service.start(OWNER_ID, startRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Pärimisjuhtumi saab avada");

        assertThat(dsl.fetchCount(table("inheritance_case"))).isZero();
    }

    @Test
    void ownerFromAnotherOrganizationCannotStartInheritanceCase() {
        insertOwner(ORG_B, OTHER_OWNER_ID, OwnerStatus.DECEASED);

        assertThatThrownBy(() -> service.start(OTHER_OWNER_ID, startRequest()))
            .isInstanceOf(ResponseStatusException.class);

        assertThat(dsl.fetchCount(table("inheritance_case"))).isZero();
        assertThat(dsl.fetchCount(table("owner_activity"))).isZero();
    }

    @Test
    void startCreatesInheritanceCaseEventAndOwnerActivity() {
        insertOwner(ORG_A, OWNER_ID, OwnerStatus.DECEASED);
        when(organizationContext.subject()).thenReturn("manager-subject");

        var details = service.start(OWNER_ID, startRequest());

        assertThat(details.ownerId()).isEqualTo(OWNER_ID);
        assertThat(details.status()).isEqualTo("NOTICE_RECEIVED");
        assertThat(details.sourceNoticeNumber()).isEqualTo("PN-2026-001");
        assertThat(details.events()).extracting(InheritanceService.InheritanceEvent::type)
            .containsExactly("CASE_STARTED");
        assertThat(dsl.fetchCount(table("owner_activity"))).isEqualTo(1);
    }

    @Test
    void addHeirPersistsHeirAndAuditEvent() {
        insertOwner(ORG_A, OWNER_ID, OwnerStatus.DECEASED);
        when(organizationContext.subject()).thenReturn("manager-subject");
        UUID caseId = service.start(OWNER_ID, startRequest()).id();

        var details = service.addHeir(caseId, new InheritanceService.SaveInheritanceHeirRequest(
            "Jaan Mets",
            "39001010022",
            null,
            "1/2",
            "CHILD",
            "+37255500102",
            "jaan@example.test",
            "READY_TO_CONTACT",
            "caller-subject",
            "MANUAL"));

        assertThat(details.heirs()).hasSize(1);
        assertThat(details.heirs().getFirst().displayName()).isEqualTo("Jaan Mets");
        assertThat(details.heirs().getFirst().contactStatus()).isEqualTo("READY_TO_CONTACT");
        assertThat(details.events()).extracting(InheritanceService.InheritanceEvent::type)
            .contains("CASE_STARTED", "HEIR_ADDED");
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table owner (
                id uuid primary key,
                organization_id uuid not null,
                personal_or_registry_code varchar(32),
                status varchar(32) not null
            )
            """);
        dsl.execute("""
            create table inheritance_case (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid not null,
                source_notice_number varchar(120),
                source_url varchar(500),
                announcement_date date,
                death_date date,
                certification_deadline date,
                notary_name varchar(180),
                notary_phone varchar(64),
                status varchar(40),
                started_at timestamp with time zone,
                ended_at timestamp with time zone,
                created_by_subject varchar(100),
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp,
                assigned_to_subject varchar(120)
            )
            """);
        dsl.execute("""
            create table inheritance_case_event (
                id uuid primary key,
                inheritance_case_id uuid not null,
                organization_id uuid not null,
                event_type varchar(80),
                description varchar(1000),
                event_data jsonb,
                created_by_subject varchar(100),
                created_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table inheritance_heir (
                id uuid primary key,
                inheritance_case_id uuid not null,
                organization_id uuid not null,
                display_name varchar(300),
                personal_code varchar(32),
                registry_code varchar(32),
                inheritance_share varchar(80),
                relation_to_deceased varchar(120),
                phone varchar(64),
                email varchar(255),
                contact_status varchar(40),
                assigned_to_subject varchar(100),
                source varchar(80),
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table owner_activity (
                organization_id uuid not null,
                owner_id uuid not null,
                activity_type varchar(32),
                content varchar(1000),
                created_by_subject varchar(100)
            )
            """);
    }

    private void insertOwner(UUID organizationId, UUID ownerId, String status) {
        dsl.insertInto(table("owner"))
            .columns(field(name("id")), field(name("organization_id")), field(name("personal_or_registry_code")), field(name("status")))
            .values(ownerId, organizationId, "48901010011", status)
            .execute();
    }

    private static InheritanceService.StartInheritanceCaseRequest startRequest() {
        return new InheritanceService.StartInheritanceCaseRequest(
            "PN-2026-001",
            "https://notices.example.test/PN-2026-001",
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 18),
            LocalDate.of(2026, 11, 20),
            "Notar Nimi",
            "+37255500100");
    }
}
