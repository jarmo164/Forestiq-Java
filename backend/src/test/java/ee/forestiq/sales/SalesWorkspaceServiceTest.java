package ee.forestiq.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerDtos.ChangeStatusRequest;
import ee.forestiq.owner.OwnerDtos.CreateReminderRequest;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.reminder.ReminderService;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.support.H2JooqTestDatabase;
import ee.forestiq.time.BusinessCalendar;

@ExtendWith(MockitoExtension.class)
class SalesWorkspaceServiceTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerService ownerService;
    @Mock private ReminderService reminderService;

    private H2JooqTestDatabase database;
    private DSLContext dsl;
    private SalesWorkspaceService service;

    @BeforeEach
    void setUp() throws Exception {
        database = H2JooqTestDatabase.create("sales-workspace-" + UUID.randomUUID());
        dsl = database.dsl();
        dsl.execute("""
            create table owner (
                id uuid primary key,
                organization_id uuid not null,
                status varchar(32) not null,
                assigned_to_subject varchar(100)
            )
            """);
        service = new SalesWorkspaceService(dsl, organizationContext, ownerService, reminderService, new BusinessCalendar());
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void legacySendToEvaluationOutcomeIsNoLongerSupported() {
        assertThatThrownBy(() -> SalesWorkspaceService.SalesOutcome.valueOf("SEND_TO_EVALUATION"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noAnswerForNewAssignedOwnerChangesStatusAndSchedulesFollowUp() {
        insertOwner("NEW", "seller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(organizationContext.subject()).thenReturn("seller-subject");

        var result = service.applyOutcome(OWNER_ID, SalesWorkspaceService.SalesOutcome.NO_ANSWER);

        assertThat(result.status()).isEqualTo("CONTACTED");
        assertThat(result.nextAction()).isEqualTo("Proovi uuesti ühendust võtta");
        assertThat(result.nextActionAt()).isNotNull();

        ArgumentCaptor<ChangeStatusRequest> statusCaptor = ArgumentCaptor.forClass(ChangeStatusRequest.class);
        verify(ownerService).changeStatus(org.mockito.ArgumentMatchers.eq(OWNER_ID), statusCaptor.capture());
        assertThat(statusCaptor.getValue().status()).isEqualTo("CONTACTED");
        assertThat(statusCaptor.getValue().comment()).contains("vastust ei saadud");

        ArgumentCaptor<CreateReminderRequest> reminderCaptor = ArgumentCaptor.forClass(CreateReminderRequest.class);
        verify(reminderService).create(reminderCaptor.capture());
        assertThat(reminderCaptor.getValue().ownerId()).isEqualTo(OWNER_ID);
        assertThat(reminderCaptor.getValue().text()).isEqualTo("Proovi uuesti ühendust võtta");
    }

    @Test
    void interestedForAlreadyInterestedOwnerAddsNoteAndSchedulesNextStep() {
        insertOwner("INTERESTED", "seller-subject");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(organizationContext.subject()).thenReturn("seller-subject");

        var result = service.applyOutcome(OWNER_ID, SalesWorkspaceService.SalesOutcome.INTERESTED);

        assertThat(result.status()).isEqualTo("INTERESTED");
        assertThat(result.nextAction()).isEqualTo("Lepi kokku hindamise järgmine samm");
        verify(ownerService).addNote(org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.contains("järgmine kontakt"));
        verify(ownerService, never()).changeStatus(any(UUID.class), any(ChangeStatusRequest.class));
        verify(reminderService).create(any(CreateReminderRequest.class));
    }

    @Test
    void outcomeIsRejectedWhenOwnerIsAssignedToAnotherSeller() {
        insertOwner("ASSIGNED", "other-seller");
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_A);
        when(organizationContext.subject()).thenReturn("seller-subject");

        assertThatThrownBy(() -> service.applyOutcome(OWNER_ID, SalesWorkspaceService.SalesOutcome.CALL_BACK))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Owner is not assigned");

        verify(ownerService, never()).changeStatus(any(UUID.class), any(ChangeStatusRequest.class));
        verify(reminderService, never()).create(any(CreateReminderRequest.class));
    }

    private void insertOwner(String status, String assignee) {
        dsl.insertInto(table("owner"))
            .columns(field(name("id")), field(name("organization_id")), field(name("status")), field(name("assigned_to_subject")))
            .values(OWNER_ID, ORG_A, status, assignee)
            .execute();
    }
}
