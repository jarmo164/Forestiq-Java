package ee.forestiq.owner.collaboration;

import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.COMPLETE_EVALUATION_NEEDS_DATA;
import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.COMPLETE_EVALUATION_VALUED;
import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.ENSURE_NEGOTIATION_AFTER_COUNTEROFFER;
import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION;
import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.REQUEST_EVALUATION;
import static ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition.START_NEGOTIATION;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.Owner;
import ee.forestiq.owner.OwnerActivityRepository;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.owner.OwnerRepository;
import ee.forestiq.owner.OwnerService;
import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.security.OrganizationContext;

/**
 * Owner-owned implementation of the cross-module application contract.
 *
 * <p>Organization and actor identity are read from the authenticated context,
 * so callers cannot widen their scope by passing a foreign organization ID.</p>
 */
@Service
public class OwnerCollaborationService implements OwnerCollaborationPort {

    private final OwnerRepository ownerRepository;
    private final OwnerActivityRepository activityRepository;
    private final OwnerAuthorizationService authorization;
    private final OwnerService ownerService;
    private final OrganizationContext organizationContext;

    public OwnerCollaborationService(
        OwnerRepository ownerRepository,
        OwnerActivityRepository activityRepository,
        OwnerAuthorizationService authorization,
        OwnerService ownerService,
        OrganizationContext organizationContext) {
        this.ownerRepository = ownerRepository;
        this.activityRepository = activityRepository;
        this.authorization = authorization;
        this.ownerService = ownerService;
        this.organizationContext = organizationContext;
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerAccess requireReadable(UUID ownerId) {
        Owner owner = owner(ownerId);
        authorization.assertCanRead(owner);
        return access(owner);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerAccess requireMutable(UUID ownerId) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        return access(owner);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertDealCreationAllowed(UUID ownerId) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        if (OwnerStatus.DECEASED.equals(owner.getStatus())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Surnud omaniku puhul tuleb kasutada pärimisjuhtumi töövoogu.");
        }
    }

    @Override
    public Optional<String> assignedSubjectRestriction() {
        return authorization.assignedSubjectRestriction();
    }

    @Override
    public boolean hasOrganizationWideAccess() {
        return authorization.hasOrganizationWideAccess();
    }

    @Override
    public void requireAssignmentManagementAccess() {
        authorization.assertCanChangeAssignment();
    }

    @Override
    @Transactional
    public void applyDealLifecycle(UUID ownerId, DealLifecycleTransition transition) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        String target = lifecycleTarget(owner.getStatus(), transition);
        if (target != null) {
            ownerService.changeStatus(ownerId, new ee.forestiq.owner.OwnerDtos.ChangeStatusRequest(
                target, lifecycleComment(transition), null));
        }
    }

    @Override
    @Transactional
    public void appendDealSystemActivity(UUID ownerId, String content) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        activityRepository.addActivity(organizationId(), owner.getId(), "SYSTEM", content, subject());
    }

    @Override
    @Transactional
    public UUID recordWorkflowContact(UUID ownerId, WorkflowContactActivity activity) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        return activityRepository.addWorkflowContact(
            organizationId(), owner.getId(), activity.activityType(), activity.content(), subject(),
            activity.contactChannel(), activity.outcomeCode(), activity.reason(),
            activity.dealCaseId(), activity.relatedReminderId());
    }

    @Override
    @Transactional
    public void recordWorkflowReminderLifecycle(UUID ownerId, WorkflowReminderLifecycleActivity activity) {
        Owner owner = owner(ownerId);
        authorization.assertCanMutate(owner);
        activityRepository.addWorkflowReminderLifecycle(
            organizationId(), owner.getId(), activity.content(), subject(),
            activity.dealCaseId(), activity.relatedReminderId());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowHistoryPage workflowHistory(UUID ownerId, WorkflowHistoryQuery query) {
        Owner owner = owner(ownerId);
        authorization.assertCanRead(owner);
        validateHistoryCursor(query.cursorCreatedAt(), query.cursorId());
        int limit = query.requestedLimit() <= 0 ? 50 : Math.min(query.requestedLimit(), 100);
        List<OwnerActivityRepository.WorkflowHistoryRow> rows = activityRepository.findWorkflowHistory(
            organizationId(),
            ownerId,
            upperOrNull(query.activityType()),
            upperOrNull(query.outcome()),
            trimToNull(query.author()),
            query.from(),
            query.to(),
            query.cursorCreatedAt(),
            query.cursorId(),
            limit + 1);

        boolean hasMore = rows.size() > limit;
        List<OwnerActivityRepository.WorkflowHistoryRow> selected = rows.stream().limit(limit).toList();
        List<WorkflowHistoryItem> items = selected.stream().map(row -> new WorkflowHistoryItem(
            row.id(),
            row.activityType(),
            row.sourceType(),
            row.content(),
            row.contactChannel(),
            row.outcomeCode(),
            row.reason(),
            row.createdBySubject(),
            row.createdAt(),
            row.relatedReminderId(),
            row.dealCaseId())).toList();

        if (!hasMore || selected.isEmpty()) {
            return new WorkflowHistoryPage(items, null, null);
        }
        OwnerActivityRepository.WorkflowHistoryRow last = selected.get(selected.size() - 1);
        return new WorkflowHistoryPage(items, last.createdAt(), last.id());
    }

    @Override
    @Transactional
    public List<ManagementAssignmentOutcome> applyManagementAssignments(
        List<ManagementAssignment> assignments,
        String rationale) {
        authorization.assertCanChangeAssignment();
        if (assignments == null || assignments.isEmpty()) return List.of();

        UUID orgId = organizationId();
        Map<UUID, ManagementAssignment> distinct = new LinkedHashMap<>();
        for (ManagementAssignment assignment : assignments) {
            if (assignment == null || assignment.ownerId() == null || assignment.toSubject() == null
                || assignment.toSubject().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Massmääramise otsus on vigane.");
            }
            if (distinct.putIfAbsent(assignment.ownerId(), assignment) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sama omanik esineb määramisotsuses mitu korda.");
            }
        }

        List<Owner> lockedOwners = ownerRepository.findByIdInAndOrganizationIdForUpdate(
            distinct.keySet(), orgId);
        if (lockedOwners.size() != distinct.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vähemalt üht määratavat omanikku ei leitud.");
        }
        Map<UUID, Owner> owners = new LinkedHashMap<>();
        lockedOwners.forEach(owner -> owners.put(owner.getId(), owner));

        List<ManagementAssignmentOutcome> outcomes = new ArrayList<>();
        String actor = subject();
        for (ManagementAssignment assignment : assignments) {
            Owner owner = owners.get(assignment.ownerId());
            String fromSubject = owner.getAssignedToSubject();
            boolean changed = !assignment.toSubject().equals(fromSubject);
            if (changed) {
                owner.setAssignedToSubject(assignment.toSubject());
                ownerRepository.save(owner);
                activityRepository.addActivity(orgId, owner.getId(), "ASSIGNED",
                    "Töö määrati kasutajale " + assignment.toSubject() + ". " + rationale, actor);
            }
            outcomes.add(new ManagementAssignmentOutcome(
                owner.getId(), owner.getDisplayName(), fromSubject, assignment.toSubject(), changed));
        }
        return List.copyOf(outcomes);
    }

    private static void validateHistoryCursor(Instant cursorCreatedAt, UUID cursorId) {
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ajaloopäringu kursor vajab nii aega kui ID-d.");
        }
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Owner owner(UUID ownerId) {
        return ownerRepository.findByIdAndOrganizationId(ownerId, organizationId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Omanikku ei leitud."));
    }

    private UUID organizationId() {
        return organizationContext.requiredOrganizationId();
    }

    private String subject() {
        return organizationContext.subject();
    }

    private static OwnerAccess access(Owner owner) {
        return new OwnerAccess(owner.getId(), owner.getDisplayName(), owner.getStatus(), owner.getAssignedToSubject(),
            owner.getPhone(), owner.getEmail(), owner.getAddress());
    }

    private static String lifecycleTarget(String currentStatus, DealLifecycleTransition transition) {
        return switch (transition) {
            case QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION ->
                !OwnerStatus.INTERESTED.equals(currentStatus) && !OwnerStatus.EVALUATION.equals(currentStatus)
                    ? OwnerStatus.INTERESTED : null;
            case REQUEST_EVALUATION -> OwnerStatus.EVALUATION;
            case COMPLETE_EVALUATION_NEEDS_DATA -> OwnerStatus.INTERESTED;
            case COMPLETE_EVALUATION_VALUED -> OwnerStatus.VALUED;
            case START_NEGOTIATION -> OwnerStatus.NEGOTIATION;
            case ENSURE_NEGOTIATION_AFTER_COUNTEROFFER ->
                OwnerStatus.NEGOTIATION.equals(currentStatus) ? null : OwnerStatus.NEGOTIATION;
        };
    }

    private static String lifecycleComment(DealLifecycleTransition transition) {
        return switch (transition) {
            case QUALIFY_IF_NOT_INTERESTED_OR_EVALUATION -> "Klient kvalifitseeriti tehingujuhtumiks.";
            case REQUEST_EVALUATION -> "Tehingujuhtum saadeti hindaja tööjärjekorda.";
            case COMPLETE_EVALUATION_NEEDS_DATA -> "Hindaja küsis tehingujuhtumi kohta täiendavaid andmeid.";
            case COMPLETE_EVALUATION_VALUED -> "Hindamisotsus valmis; klient vajab pakkumisega järelkontakti.";
            case START_NEGOTIATION -> "Hindamisotsuse põhjal alustati kliendiga läbirääkimisi.";
            case ENSURE_NEGOTIATION_AFTER_COUNTEROFFER -> "Klient esitas tehingujuhtumi vastupakkumise.";
        };
    }
}
