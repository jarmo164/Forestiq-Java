package ee.forestiq.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;
import ee.forestiq.time.BusinessCalendar;

@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARCEL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_PARCEL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerCollaborationPort ownerCollaboration;

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private DealService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("deal-service-" + UUID.randomUUID());
        dsl = database.dsl();
        createSchema();
        service = new DealService(dsl, organizationContext, ownerCollaboration,
            new DealQueryRepository(dsl), new BusinessCalendar());
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(organizationContext.subject()).thenReturn("sales-subject");
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void createDealRequestsEvaluationAndRecordsAuditTrail() {
        insertOwner(OWNER_ID, OwnerStatus.ASSIGNED, "caller-subject");
        insertParcel(PARCEL_ID, OWNER_ID, "12345:001:0001");

        var details = service.create(OWNER_ID, new DealService.CreateDealRequest(
            "FOREST", List.of(PARCEL_ID), "decision-maker", "THIS_YEAR", BigDecimal.valueOf(120000),
            "Client is ready to sell", true, null));

        assertThat(details.stage()).isEqualTo("EVALUATION_REQUESTED");
        assertThat(details.evaluatorSubject()).isNull();
        assertThat(details.evaluationDueAt()).isNotNull();
        assertThat(details.evaluationDueAt().atZone(ZoneId.of("Europe/Tallinn")).getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
        assertThat(details.parcels()).extracting(DealService.DealParcel::id).containsExactly(PARCEL_ID);
        assertThat(details.events()).extracting(DealService.DealEvent::type).contains("QUALIFIED", "EVALUATION_REQUESTED");
        verify(ownerCollaboration).assertDealCreationAllowed(OWNER_ID);
        verify(ownerCollaboration).appendDealSystemActivity(OWNER_ID, "Loodi tehingujuhtum: Hindaja koostab väärtusotsuse.");
        verify(ownerCollaboration).applyDealLifecycle(OWNER_ID, DealLifecycleTransition.QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION);
        verify(ownerCollaboration).applyDealLifecycle(OWNER_ID, DealLifecycleTransition.REQUEST_EVALUATION);
    }

    @Test
    void createDealRejectsParcelOutsideOwner() {
        insertOwner(OWNER_ID, OwnerStatus.INTERESTED, "caller-subject");
        insertOwner(OTHER_OWNER_ID, OwnerStatus.INTERESTED, "caller-subject");
        insertParcel(OTHER_PARCEL_ID, OTHER_OWNER_ID, "12345:001:0002");

        assertThatThrownBy(() -> service.create(OWNER_ID, new DealService.CreateDealRequest(
            "LAND", List.of(OTHER_PARCEL_ID), null, null, null, null, false, null)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Valitud kinnistu");
        verify(ownerCollaboration, never()).applyDealLifecycle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(dsl.fetchCount(table("deal_case"))).isZero();
    }

    @Test
    void submitEvaluationMovesDealToValuedAndCreatesSalesReminder() {
        insertOwner(OWNER_ID, OwnerStatus.EVALUATION, "caller-subject");
        UUID dealId = insertDeal("EVALUATING", "sales-subject", "sales-subject");

        var details = service.submitEvaluation(dealId, new DealService.SubmitEvaluationRequest(
            "APPROVED", BigDecimal.valueOf(90000), BigDecimal.valueOf(110000), BigDecimal.valueOf(100000),
            BigDecimal.valueOf(85000), BigDecimal.valueOf(105000), null, "Good access", "No known risks", null));

        assertThat(details.stage()).isEqualTo("VALUED");
        assertThat(details.latestEvaluation()).isNotNull();
        assertThat(details.latestEvaluation().revision()).isEqualTo(1);
        assertThat(dsl.fetchCount(table("reminder"))).isEqualTo(1);
        verify(ownerCollaboration).applyDealLifecycle(OWNER_ID, DealLifecycleTransition.COMPLETE_EVALUATION_VALUED);
    }

    @Test
    void unassignedEvaluationMustBeClaimedBeforeSubmission() {
        insertOwner(OWNER_ID, OwnerStatus.EVALUATION, "caller-subject");
        UUID dealId = insertDeal("EVALUATION_REQUESTED", "sales-subject", null);
        assertThatThrownBy(() -> service.submitEvaluation(dealId, new DealService.SubmitEvaluationRequest(
            "APPROVED", null, null, BigDecimal.valueOf(100000), null, null, null, null, null, null)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Võta hindamisülesanne");
        assertThat(dsl.fetchCount(table("deal_evaluation"))).isZero();
    }

    @Test
    void evaluatorCannotSubmitAnotherEvaluatorsTask() {
        insertOwner(OWNER_ID, OwnerStatus.EVALUATION, "caller-subject");
        UUID dealId = insertDeal("EVALUATING", "sales-subject", "other-evaluator-subject");
        assertThatThrownBy(() -> service.submitEvaluation(dealId, new DealService.SubmitEvaluationRequest(
            "APPROVED", null, null, BigDecimal.valueOf(100000), null, null, null, null, null, null)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403 FORBIDDEN");
        assertThat(dsl.fetchCount(table("deal_evaluation"))).isZero();
    }

    @Test
    void evaluatorCanClaimUnassignedTask() {
        insertOwner(OWNER_ID, OwnerStatus.EVALUATION, "caller-subject");
        UUID dealId = insertDeal("EVALUATION_REQUESTED", "sales-subject", null);
        var details = service.claimEvaluation(dealId);
        assertThat(details.stage()).isEqualTo("EVALUATING");
        assertThat(details.evaluatorSubject()).isEqualTo("sales-subject");
        assertThat(details.events()).extracting(DealService.DealEvent::type).contains("EVALUATION_CLAIMED");
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table owner (
                id uuid primary key, organization_id uuid not null, display_name varchar(300),
                personal_or_registry_code varchar(100), email varchar(255), phone varchar(64),
                status varchar(32) not null, assigned_to_subject varchar(100), search_document varchar(2000),
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp,
                owner_type varchar(32), address varchar(500), info varchar(2000),
                status_set_at timestamp with time zone, last_cadastre_list_refresh timestamp with time zone,
                out_of_admin_search_from timestamp with time zone, out_of_admin_search_to timestamp with time zone,
                out_of_admin_search_reason varchar(500), lock_version bigint default 0,
                source_display_name varchar(300), source_owner_type varchar(32)
            )
            """);
        dsl.execute("""
            create table land_parcel (
                id uuid primary key, organization_id uuid not null, owner_id uuid,
                cadastral_code varchar(32) not null, area_hectares numeric(12, 4), name varchar(300),
                county varchar(120), municipality varchar(120), updated_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table deal_case (
                id uuid primary key, organization_id uuid not null, owner_id uuid not null, sale_subject varchar(32),
                stage varchar(40), sales_subject varchar(100), evaluator_subject varchar(100), decision_maker varchar(180),
                sale_timeframe varchar(80), price_expectation numeric(14, 2), qualification_notes varchar(1000),
                next_action varchar(500), next_action_due_at timestamp with time zone, evaluation_due_at timestamp with time zone,
                stage_set_at timestamp with time zone, closed_at timestamp with time zone, created_by_subject varchar(100),
                created_at timestamp with time zone default current_timestamp, updated_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("create table deal_case_parcel (deal_case_id uuid not null, organization_id uuid not null, parcel_id uuid not null)");
        dsl.execute("""
            create table deal_case_event (
                id uuid primary key, organization_id uuid not null, deal_case_id uuid not null, event_type varchar(80),
                description varchar(1000), event_data jsonb, created_by_subject varchar(100),
                created_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table owner_activity (
                organization_id uuid not null, owner_id uuid not null, activity_type varchar(32), content varchar(1000),
                created_by_subject varchar(100), created_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table deal_evaluation (
                id uuid primary key, deal_case_id uuid not null, organization_id uuid not null, revision int,
                status varchar(40), estimated_min_price numeric(14, 2), estimated_max_price numeric(14, 2),
                recommended_purchase_price numeric(14, 2), internal_min_price numeric(14, 2), proposed_offer_price numeric(14, 2),
                offer_valid_until date, assumptions varchar(1000), risks varchar(1000), returned_reason varchar(1000),
                created_by_subject varchar(100), submitted_at timestamp with time zone,
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("create table reminder (organization_id uuid not null, creator varchar(100), owner_id uuid, reminder_text varchar(500), due_time timestamp with time zone)");
    }

    private void insertOwner(UUID ownerId, String status, String assignee) {
        dsl.insertInto(table("owner"))
            .columns(field(name("id")), field(name("organization_id")), field(name("display_name")),
                field(name("status")), field(name("assigned_to_subject")))
            .values(ownerId, ORG_A, "Mari Mets", status, assignee).execute();
    }

    private void insertParcel(UUID parcelId, UUID ownerId, String cadastralCode) {
        dsl.insertInto(table("land_parcel"))
            .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")),
                field(name("cadastral_code")), field(name("area_hectares")), field(name("name")))
            .values(parcelId, ORG_A, ownerId, cadastralCode, BigDecimal.TEN, "Test parcel").execute();
    }

    private UUID insertDeal(String stage, String salesSubject, String evaluatorSubject) {
        UUID dealId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        dsl.insertInto(table("deal_case"))
            .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")),
                field(name("sale_subject")), field(name("stage")), field(name("sales_subject")), field(name("evaluator_subject")),
                field(name("stage_set_at")), field(name("created_by_subject")))
            .values(dealId, ORG_A, OWNER_ID, "FOREST", stage, salesSubject, evaluatorSubject, Instant.now(), "creator").execute();
        return dealId;
    }
}
