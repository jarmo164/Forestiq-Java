package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;

class DealLifecycleServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Connection connection;
    private DSLContext dsl;
    private OwnerCollaborationPort ownerCollaboration;
    private DealLifecycleService lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:deal-lifecycle-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "");
        dsl = DSL.using(connection, SQLDialect.H2);
        createSchema();

        OrganizationContext organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(organizationContext.subject()).thenReturn("sales-subject");
        ownerCollaboration = mock(OwnerCollaborationPort.class);
        lifecycle = new DealLifecycleService(
            mock(DealService.class),
            mock(DealCommercialService.class),
            dsl,
            organizationContext,
            ownerCollaboration);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void canonicalLossWritesOutcomeEventAndOwnerActivityThroughOneCommand() {
        insertDeal("VALUED");
        dsl.execute("insert into deal_loss_reason_definition (organization_id, code, label, active, sort_order) values (?, 'PRICE_GAP', 'Hinnavahe', true, 1)",
            ORGANIZATION_ID);

        var outcome = lifecycle.lose(DEAL_ID,
            new DealLifecycleService.LoseDealCommand("PRICE_GAP", null, "Hinnaootus ei sobinud", null));

        assertThat(outcome.reasonCode()).isEqualTo("PRICE_GAP");
        assertThat(outcome.lostFromStage()).isEqualTo("VALUED");
        assertThat(dsl.select(DEAL_CASE.STAGE).from(DEAL_CASE).where(DEAL_CASE.ID.eq(DEAL_ID))
            .fetchOne(DEAL_CASE.STAGE)).isEqualTo("LOST");
        assertThat(dsl.fetchCount(DSL.table("deal_loss_outcome"))).isEqualTo(1);
        assertThat(dsl.fetchCount(DSL.table("deal_case_event"), DSL.field("event_type").eq("DEAL_LOST"))).isEqualTo(1);
        verify(ownerCollaboration).appendDealSystemActivity(eq(OWNER_ID), contains("Hinnavahe"));
    }

    @Test
    void terminalDealCannotReceiveAnotherLifecycleMutation() {
        insertDeal("LOST");
        dsl.execute("insert into deal_loss_reason_definition (organization_id, code, label, active, sort_order) values (?, 'PRICE_GAP', 'Hinnavahe', true, 1)",
            ORGANIZATION_ID);

        assertThatThrownBy(() -> lifecycle.lose(DEAL_ID,
            new DealLifecycleService.LoseDealCommand("PRICE_GAP", null, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED");
        assertThat(dsl.fetchCount(DSL.table("deal_loss_outcome"))).isZero();
    }

    @Test
    void nextActionUsesCompareAndSetAfterOwnerAuthorization() {
        insertDeal("VALUED");
        Instant dueAt = Instant.now().plusSeconds(3600);

        lifecycle.updateNextAction(DEAL_ID,
            new DealLifecycleService.UpdateNextActionCommand("Võta kliendiga ühendust", dueAt));

        assertThat(dsl.select(DEAL_CASE.NEXT_ACTION).from(DEAL_CASE).where(DEAL_CASE.ID.eq(DEAL_ID))
            .fetchOne(DEAL_CASE.NEXT_ACTION))
            .isEqualTo("Võta kliendiga ühendust");
        assertThat(dsl.fetchCount(DSL.table("deal_case_event"), DSL.field("event_type").eq("NEXT_ACTION_UPDATED")))
            .isEqualTo(1);
        verify(ownerCollaboration).requireMutable(OWNER_ID);
    }

    private void insertDeal(String stage) {
        dsl.execute("""
            insert into deal_case
              (id, organization_id, owner_id, stage, sales_subject, next_action, stage_set_at, updated_at)
            values (?, ?, ?, ?, 'sales-subject', 'Võta ühendust', current_timestamp, current_timestamp)
            """, DEAL_ID, ORGANIZATION_ID, OWNER_ID, stage);
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table deal_case (
              id uuid primary key,
              organization_id uuid not null,
              owner_id uuid not null,
              stage varchar(40) not null,
              sales_subject varchar(100) not null,
              next_action text,
              next_action_due_at timestamp with time zone,
              stage_set_at timestamp with time zone,
              closed_at timestamp with time zone,
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
              created_by_subject varchar(100) not null
            )
            """);
        dsl.execute("""
            create table deal_loss_reason_definition (
              organization_id uuid not null,
              code varchar(64) not null,
              label varchar(160) not null,
              active boolean not null,
              sort_order integer not null,
              primary key (organization_id, code)
            )
            """);
        dsl.execute("""
            create table deal_loss_outcome (
              organization_id uuid not null,
              deal_case_id uuid not null,
              reason_code varchar(64) not null,
              lost_from_stage varchar(40) not null,
              sales_subject varchar(100) not null,
              note text,
              follow_up_at timestamp with time zone,
              created_by_subject varchar(100) not null,
              created_at timestamp with time zone not null default current_timestamp
            )
            """);
        dsl.execute("""
            create table reminder (
              id bigint generated by default as identity primary key,
              organization_id uuid not null,
              creator varchar(100) not null,
              owner_id uuid not null,
              reminder_text text not null,
              due_time timestamp with time zone not null,
              assignee_subject varchar(100),
              status varchar(32),
              activity_type varchar(32),
              priority_reason varchar(300),
              deal_case_id uuid,
              updated_at timestamp with time zone
            )
            """);
        dsl.execute("""
            create table reminder_event (
              organization_id uuid not null,
              reminder_id bigint not null,
              owner_id uuid not null,
              deal_case_id uuid,
              event_type varchar(32) not null,
              new_due_time timestamp with time zone,
              to_assignee varchar(100),
              reason varchar(300),
              created_by_subject varchar(100) not null
            )
            """);
    }
}
