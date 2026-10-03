package ee.forestiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.deal.contractsource.WonDealContractSourcePort;
import ee.forestiq.deal.contractsource.WonDealContractSourceService;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;

class DealContractSourceServiceTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARCEL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OFFER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private Connection connection;
    private DSLContext dsl;
    private OwnerCollaborationPort ownerCollaboration;
    private DealContractSourceService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:deal-contract-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "");
        dsl = DSL.using(connection, SQLDialect.H2);
        createSchema();
        OrganizationContext context = mock(OrganizationContext.class);
        ownerCollaboration = mock(OwnerCollaborationPort.class);
        when(context.requiredOrganizationId()).thenReturn(ORG);
        WonDealContractSourcePort sourcePort = new WonDealContractSourceService(
            dsl, context, new ObjectMapper(), ownerCollaboration);
        service = new DealContractSourceService(sourcePort);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void wonDealBuildsDraftFromAcceptedOfferAndAllDealParcels() {
        seedDeal("WON");
        dsl.execute("insert into deal_case_event (id, organization_id, deal_case_id, event_type, event_data, created_at) values (?, ?, ?, 'OFFER_PREPARED', ?::jsonb, current_timestamp)",
            UUID.randomUUID(), ORG, DEAL, "{\"entryId\":\"" + OFFER + "\",\"amount\":\"125000\",\"terms\":\"Tasumine notaripäeval\"}");
        dsl.execute("insert into deal_case_event (id, organization_id, deal_case_id, event_type, event_data, created_at) values (?, ?, ?, 'DEAL_WON', ?::jsonb, current_timestamp)",
            UUID.randomUUID(), ORG, DEAL, "{\"entryId\":\"" + OFFER + "\",\"amount\":\"125000\"}");

        var draft = service.draft(DEAL);

        assertThat(draft.dealId()).isEqualTo(DEAL);
        assertThat(draft.offerEntryId()).isEqualTo(OFFER);
        assertThat(draft.acceptedPrice()).isEqualByComparingTo("125000");
        assertThat(draft.acceptedTerms()).isEqualTo("Tasumine notaripäeval");
        assertThat(draft.seller().name()).isEqualTo("Mari Mets");
        assertThat(draft.parcels()).singleElement().satisfies(parcel -> {
            assertThat(parcel.cadastralCode()).isEqualTo("10001:001:0001");
            assertThat(parcel.areaHectares()).isEqualByComparingTo("20.2");
        });
        assertThatThrownBy(() -> draft.parcels().add(null)).isInstanceOf(UnsupportedOperationException.class);
        verify(ownerCollaboration).requireReadable(OWNER);

        assertThat(service.validateSource(DEAL, OFFER).offerEntryId()).isEqualTo(OFFER);
    }

    @Test
    void unlinkedProductionSourceIsRejected() {
        assertThatThrownBy(() -> service.validateSource(null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> service.validateSource(DEAL, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void nonWonDealAndWrongOfferAreRejected() {
        seedDeal("LOST");
        assertThatThrownBy(() -> service.draft(DEAL))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("412 PRECONDITION_FAILED");

        dsl.update(ee.forestiq.jooq.Tables.DEAL_CASE)
            .set(ee.forestiq.jooq.Tables.DEAL_CASE.STAGE, "WON")
            .where(ee.forestiq.jooq.Tables.DEAL_CASE.ID.eq(DEAL))
            .execute();
        dsl.execute("insert into deal_case_event (id, organization_id, deal_case_id, event_type, event_data, created_at) values (?, ?, ?, 'OFFER_PREPARED', ?::jsonb, current_timestamp)",
            UUID.randomUUID(), ORG, DEAL, "{\"entryId\":\"" + OFFER + "\",\"amount\":\"125000\",\"terms\":\"\"}");
        dsl.execute("insert into deal_case_event (id, organization_id, deal_case_id, event_type, event_data, created_at) values (?, ?, ?, 'DEAL_WON', ?::jsonb, current_timestamp)",
            UUID.randomUUID(), ORG, DEAL, "{\"entryId\":\"" + OFFER + "\",\"amount\":\"125000\"}");

        UUID wrongOffer = UUID.fromString("66666666-6666-6666-6666-666666666666");
        assertThatThrownBy(() -> service.validateSource(DEAL, wrongOffer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST");
    }

    private void seedDeal(String stage) {
        dsl.execute("insert into owner (id, organization_id, display_name, personal_or_registry_code, address) values (?, ?, 'Mari Mets', '48901010011', 'Metsa tee 1')", OWNER, ORG);
        dsl.execute("insert into deal_case (id, organization_id, owner_id, stage) values (?, ?, ?, ?)", DEAL, ORG, OWNER, stage);
        dsl.execute("insert into land_parcel (id, organization_id, cadastral_code, address, area_hectares) values (?, ?, '10001:001:0001', 'Metsa tee 1', 20.2)", PARCEL, ORG);
        dsl.execute("insert into deal_case_parcel (deal_case_id, organization_id, parcel_id) values (?, ?, ?)", DEAL, ORG, PARCEL);
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("create table owner (id uuid primary key, organization_id uuid not null, display_name varchar(300), personal_or_registry_code varchar(100), address varchar(1000))");
        dsl.execute("create table deal_case (id uuid primary key, organization_id uuid not null, owner_id uuid not null, stage varchar(40))");
        dsl.execute("create table land_parcel (id uuid primary key, organization_id uuid not null, cadastral_code varchar(100), address varchar(1000), area_hectares numeric(12,4))");
        dsl.execute("create table deal_case_parcel (deal_case_id uuid not null, organization_id uuid not null, parcel_id uuid not null)");
        dsl.execute("create table deal_case_event (id uuid primary key, organization_id uuid not null, deal_case_id uuid not null, event_type varchar(80), event_data jsonb, created_at timestamp with time zone)");
    }
}
