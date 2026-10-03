package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.time.BusinessCalendar;

class DealCommercialServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Connection connection;
    private DSLContext dsl;
    private DealCommercialService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:deal-commercial-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "");
        dsl = DSL.using(connection, SQLDialect.H2);
        createSchema();

        OrganizationContext context = mock(OrganizationContext.class);
        OwnerCollaborationPort ownerCollaboration = mock(OwnerCollaborationPort.class);
        DealQueryRepository queryRepository = mock(DealQueryRepository.class);

        when(context.requiredOrganizationId()).thenReturn(ORG_ID);
        when(context.subject()).thenReturn("sales-subject");
        when(queryRepository.requireDeal(ORG_ID, DEAL_ID)).thenAnswer(ignored -> currentDeal());

        service = new DealCommercialService(dsl, context, ownerCollaboration, queryRepository, new BusinessCalendar());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void valuedDealCanPrepareAndSendVersionedOffer() {
        insertDeal("VALUED");

        var prepared = service.prepareOffer(DEAL_ID,
            new DealCommercialService.PrepareOfferRequest(BigDecimal.valueOf(125000), LocalDate.now().plusDays(14), "Tasumine notaripäeval"));
        assertThat(prepared.stage()).isEqualTo("OFFER_PREPARED");
        assertThat(prepared.offers()).hasSize(1);
        assertThat(prepared.offers().getFirst().revision()).isEqualTo(1);
        assertThat(prepared.offers().getFirst().amount()).isEqualByComparingTo("125000");

        var sent = service.sendOffer(DEAL_ID);
        assertThat(sent.stage()).isEqualTo("OFFER_SENT");
        assertThat(sent.offers().getFirst().sentAt()).isNotNull();

        assertThatThrownBy(() -> service.prepareOffer(DEAL_ID,
            new DealCommercialService.PrepareOfferRequest(BigDecimal.valueOf(126000), null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED");
    }

    @Test
    void counterOfferCanBeAcceptedAndTerminalDealCannotMutate() {
        insertDeal("VALUED");
        service.prepareOffer(DEAL_ID, new DealCommercialService.PrepareOfferRequest(BigDecimal.valueOf(125000), null, null));
        service.sendOffer(DEAL_ID);

        var negotiating = service.recordCounterOffer(DEAL_ID,
            new DealCommercialService.CounterOfferRequest(BigDecimal.valueOf(132000), "Klient soovib kõrgemat hinda"));
        assertThat(negotiating.stage()).isEqualTo("NEGOTIATION");
        assertThat(negotiating.offers()).hasSize(2);
        var counter = negotiating.offers().get(1);
        assertThat(counter.kind()).isEqualTo("CUSTOMER_COUNTEROFFER");
        assertThat(counter.amount()).isEqualByComparingTo("132000");

        var won = service.win(DEAL_ID, new DealCommercialService.WinDealRequest(counter.entryId(), "Kokkulepe saavutatud"));
        assertThat(won.stage()).isEqualTo("WON");
        assertThat(won.closedAt()).isNotNull();
        assertThat(won.offers().stream().filter(o -> o.entryId().equals(counter.entryId())).findFirst().orElseThrow().acceptedAt()).isNotNull();

        assertThatThrownBy(() -> service.recordCounterOffer(DEAL_ID,
            new DealCommercialService.CounterOfferRequest(BigDecimal.valueOf(133000), null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Lõpetatud tehingujuhtumit");
    }

    @Test
    void invalidJumpFromValuedDirectlyToSendIsRejected() {
        insertDeal("VALUED");

        assertThatThrownBy(() -> service.sendOffer(DEAL_ID))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED");
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table deal_case (
              id uuid primary key,
              organization_id uuid not null,
              owner_id uuid not null,
              sale_subject varchar(16),
              stage varchar(40) not null,
              sales_subject varchar(100) not null,
              evaluator_subject varchar(100),
              decision_maker varchar(300),
              sale_timeframe varchar(120),
              price_expectation numeric(14,2),
              qualification_notes text,
              next_action text,
              next_action_due_at timestamp with time zone,
              evaluation_due_at timestamp with time zone,
              stage_set_at timestamp with time zone,
              closed_at timestamp with time zone,
              created_by_subject varchar(100),
              created_at timestamp with time zone default current_timestamp,
              updated_at timestamp with time zone
            )
            """);
        dsl.execute("""
            create table deal_case_event (
              id uuid primary key,
              organization_id uuid not null,
              deal_case_id uuid not null,
              event_type varchar(80) not null,
              description varchar(1000) not null,
              event_data jsonb,
              created_by_subject varchar(100) not null,
              created_at timestamp with time zone default current_timestamp
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

    private void insertDeal(String stage) {
        dsl.insertInto(table("deal_case"))
            .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")), field(name("sale_subject")), field(name("stage")),
                field(name("sales_subject")), field(name("next_action")), field(name("stage_set_at")), field(name("created_by_subject")), field(name("updated_at")))
            .values(DEAL_ID, ORG_ID, OWNER_ID, "FOREST", stage, "sales-subject", "next",
                java.time.OffsetDateTime.now(), "sales-subject", java.time.OffsetDateTime.now())
            .execute();
    }

    private Record currentDeal() {
        return dsl.selectFrom(DEAL_CASE)
            .where(DEAL_CASE.ID.eq(DEAL_ID).and(DEAL_CASE.ORGANIZATION_ID.eq(ORG_ID)))
            .fetchOne();
    }
}
