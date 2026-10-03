package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
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

class DealConcurrencyTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Connection connection;
    private DSLContext dsl;
    private OrganizationContext organizationContext;
    private OwnerCollaborationPort ownerCollaboration;
    private DealQueryRepository queryRepository;
    private Record staleDeal;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:deal-concurrency-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "");
        dsl = DSL.using(connection, SQLDialect.H2);
        createSchema();

        organizationContext = mock(OrganizationContext.class);
        ownerCollaboration = mock(OwnerCollaborationPort.class);
        queryRepository = mock(DealQueryRepository.class);
        staleDeal = mock(Record.class);

        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(organizationContext.subject()).thenReturn("sales-subject");
        when(queryRepository.requireDeal(ORG_ID, DEAL_ID)).thenReturn(staleDeal);
        when(staleDeal.get(DEAL_CASE.OWNER_ID)).thenReturn(OWNER_ID);
        when(staleDeal.get(DEAL_CASE.SALES_SUBJECT)).thenReturn("sales-subject");
        when(staleDeal.get(DEAL_CASE.EVALUATOR_SUBJECT)).thenReturn("sales-subject");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void staleNegotiationTransitionReturnsConflictInsteadOfOverwritingNewerStage() {
        insertDeal("NEGOTIATION");
        when(staleDeal.get(DEAL_CASE.STAGE)).thenReturn("VALUED");

        DealService service = service();

        assertThatThrownBy(() -> service.startNegotiation(DEAL_ID))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409 CONFLICT");

        assertThat(currentStage()).isEqualTo("NEGOTIATION");
        verify(ownerCollaboration, never()).applyDealLifecycle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleEvaluationSubmissionRollsBackRevisionWhenExpectedStageNoLongerMatches() throws Exception {
        insertDeal("VALUED");
        when(staleDeal.get(DEAL_CASE.STAGE)).thenReturn("EVALUATION_REQUESTED");
        connection.setAutoCommit(false);

        DealService service = service();
        var request = new DealService.SubmitEvaluationRequest(
            "APPROVED", null, null, java.math.BigDecimal.valueOf(100000), null, null,
            null, null, null, null);

        assertThatThrownBy(() -> service.submitEvaluation(DEAL_ID, request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409 CONFLICT");

        connection.rollback();
        connection.setAutoCommit(true);
        assertThat(dsl.fetchCount(table("deal_evaluation"))).isZero();
        assertThat(currentStage()).isEqualTo("VALUED");
        verify(ownerCollaboration, never()).appendDealSystemActivity(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private DealService service() {
        return new DealService(dsl, organizationContext, ownerCollaboration, queryRepository, new BusinessCalendar());
    }

    private void createSchema() {
        dsl.execute("""
            create table deal_case (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid not null,
                stage varchar(40) not null,
                sales_subject varchar(100),
                stage_set_at timestamp with time zone,
                next_action varchar(500),
                next_action_due_at timestamp with time zone,
                updated_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table deal_evaluation (
                id uuid primary key,
                deal_case_id uuid not null,
                organization_id uuid not null,
                revision int not null,
                status varchar(40),
                estimated_min_price numeric(14, 2),
                estimated_max_price numeric(14, 2),
                recommended_purchase_price numeric(14, 2),
                internal_min_price numeric(14, 2),
                proposed_offer_price numeric(14, 2),
                offer_valid_until date,
                assumptions varchar(1000),
                risks varchar(1000),
                returned_reason varchar(1000),
                created_by_subject varchar(100),
                submitted_at timestamp with time zone,
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp,
                unique (deal_case_id, revision)
            )
            """);
    }

    private void insertDeal(String stage) {
        dsl.insertInto(table("deal_case"))
            .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")),
                field(name("stage")), field(name("sales_subject")))
            .values(DEAL_ID, ORG_ID, OWNER_ID, stage, "sales-subject")
            .execute();
    }

    private String currentStage() {
        return dsl.select(field(name("stage"), String.class))
            .from(table("deal_case"))
            .where(field(name("id"), UUID.class).eq(DEAL_ID))
            .fetchOne(0, String.class);
    }
}
