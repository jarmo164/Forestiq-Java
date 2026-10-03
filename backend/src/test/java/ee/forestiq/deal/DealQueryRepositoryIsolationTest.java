package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ee.forestiq.support.H2JooqTestDatabase;

class DealQueryRepositoryIsolationTest {
    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARCEL_A = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PARCEL_B = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private DealQueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("deal-query-isolation-" + UUID.randomUUID());
        dsl = database.dsl();
        createSchema();
        repository = new DealQueryRepository(dsl);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void detailReadCannotAttachChildRowsFromAnotherOrganization() {
        dsl.execute("""
            insert into land_parcel (id, organization_id, cadastral_code, area_hectares, name)
            values (?, ?, '11111:111:1111', 10.0, 'Org A parcel'),
                   (?, ?, '22222:222:2222', 20.0, 'Org B parcel')
            """, PARCEL_A, ORG_A, PARCEL_B, ORG_B);
        dsl.execute("""
            insert into deal_case_parcel (deal_case_id, organization_id, parcel_id)
            values (?, ?, ?), (?, ?, ?)
            """, DEAL_ID, ORG_A, PARCEL_A, DEAL_ID, ORG_B, PARCEL_B);
        dsl.execute("""
            insert into deal_case_event
                (id, organization_id, deal_case_id, event_type, description, created_by_subject)
            values
                ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', ?, ?, 'ORG_A_EVENT', 'visible', 'user-a'),
                ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', ?, ?, 'ORG_B_EVENT', 'hidden', 'user-b')
            """, ORG_A, DEAL_ID, ORG_B, DEAL_ID);
        dsl.execute("""
            insert into deal_evaluation
                (id, deal_case_id, organization_id, revision, status, recommended_purchase_price, created_by_subject)
            values ('cccccccc-cccc-cccc-cccc-cccccccccccc', ?, ?, 1, 'APPROVED', 999999.00, 'user-b')
            """, DEAL_ID, ORG_B);

        Record row = mock(Record.class);
        when(row.get(DEAL_CASE.ID)).thenReturn(DEAL_ID);
        when(row.get(DEAL_CASE.ORGANIZATION_ID)).thenReturn(ORG_A);

        DealService.DealDetails details = repository.details(row);

        assertThat(details.parcels()).extracting(DealService.DealParcel::id).containsExactly(PARCEL_A);
        assertThat(details.events()).extracting(DealService.DealEvent::type).containsExactly("ORG_A_EVENT");
        assertThat(details.latestEvaluation()).isNull();
    }

    private void createSchema() {
        dsl.execute("create domain if not exists jsonb as json");
        dsl.execute("""
            create table land_parcel (
                id uuid primary key,
                organization_id uuid not null,
                cadastral_code varchar(32) not null,
                area_hectares numeric(12,4),
                name varchar(300)
            )
            """);
        dsl.execute("""
            create table deal_case_parcel (
                deal_case_id uuid not null,
                organization_id uuid not null,
                parcel_id uuid not null
            )
            """);
        dsl.execute("""
            create table deal_case_event (
                id uuid primary key,
                organization_id uuid not null,
                deal_case_id uuid not null,
                event_type varchar(80),
                description varchar(1000),
                event_data jsonb,
                created_by_subject varchar(100),
                created_at timestamp with time zone default current_timestamp
            )
            """);
        dsl.execute("""
            create table deal_evaluation (
                id uuid primary key,
                deal_case_id uuid not null,
                organization_id uuid not null,
                revision int,
                status varchar(40),
                estimated_min_price numeric(14,2),
                estimated_max_price numeric(14,2),
                recommended_purchase_price numeric(14,2),
                internal_min_price numeric(14,2),
                proposed_offer_price numeric(14,2),
                offer_valid_until date,
                assumptions varchar(1000),
                risks varchar(1000),
                returned_reason varchar(1000),
                created_by_subject varchar(100),
                submitted_at timestamp with time zone,
                created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp
            )
            """);
    }
}
