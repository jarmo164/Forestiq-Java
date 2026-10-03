package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.time.BusinessCalendar;

@ExtendWith(MockitoExtension.class)
class DealAuthorizationBoundaryTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private DSLContext dsl;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerCollaborationPort ownerCollaboration;
    @Mock private DealQueryRepository queryRepository;
    @Mock private Record deal;

    @Test
    void evaluationQueueCombinesCallerAndEvaluatorRestrictions() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(organizationContext.subject()).thenReturn("caller-subject");
        when(ownerCollaboration.assignedSubjectRestriction()).thenReturn(Optional.of("caller-subject"));
        when(queryRepository.evaluationQueue(ORG_ID, Optional.of("caller-subject"), Optional.of("caller-subject"))).thenReturn(List.of());

        service().evaluationQueue();

        verify(queryRepository).evaluationQueue(ORG_ID, Optional.of("caller-subject"), Optional.of("caller-subject"));
    }

    @Test
    void organizationWideEvaluatorQueueIsNotRestrictedByEvaluatorSubject() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(ownerCollaboration.hasOrganizationWideAccess()).thenReturn(true);
        when(ownerCollaboration.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(queryRepository.evaluationQueue(ORG_ID, Optional.empty(), Optional.empty())).thenReturn(List.of());

        service().evaluationQueue();

        verify(queryRepository).evaluationQueue(ORG_ID, Optional.empty(), Optional.empty());
    }

    @Test
    void submitEvaluationAuthorizesDealOwnerThroughTypedPortBeforeMutation() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(organizationContext.subject()).thenReturn("caller-subject");
        when(queryRepository.requireDeal(ORG_ID, DEAL_ID)).thenReturn(deal);
        when(deal.get(DEAL_CASE.OWNER_ID)).thenReturn(OWNER_ID);
        org.mockito.Mockito.doThrow(new AccessDeniedException("Owner is not assigned to the current caller"))
            .when(ownerCollaboration).requireMutable(OWNER_ID);

        assertThatThrownBy(() -> service().submitEvaluation(DEAL_ID, null))
            .isInstanceOf(AccessDeniedException.class);

        verify(ownerCollaboration).requireMutable(OWNER_ID);
        verifyNoInteractions(dsl);
    }

    @Test
    void negotiationAuthorizesDealOwnerThroughTypedPortBeforeTransition() {
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(organizationContext.subject()).thenReturn("caller-subject");
        when(queryRepository.requireDeal(ORG_ID, DEAL_ID)).thenReturn(deal);
        when(deal.get(DEAL_CASE.OWNER_ID)).thenReturn(OWNER_ID);
        org.mockito.Mockito.doThrow(new AccessDeniedException("Owner is not assigned to the current caller"))
            .when(ownerCollaboration).requireMutable(OWNER_ID);

        assertThatThrownBy(() -> service().startNegotiation(DEAL_ID))
            .isInstanceOf(AccessDeniedException.class);

        verify(ownerCollaboration).requireMutable(OWNER_ID);
        verifyNoInteractions(dsl);
    }

    private DealService service() {
        return new DealService(dsl, organizationContext, ownerCollaboration, queryRepository, new BusinessCalendar());
    }
}
