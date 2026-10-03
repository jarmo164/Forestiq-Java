package ee.forestiq.owner;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerDtos.*;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.web.ApiExceptionHandler;

@Service
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final OwnerQueryRepository queryRepository;
    private final OwnerParcelRepository parcelRepository;
    private final OwnerActivityRepository activityRepository;
    private final OwnerFollowingRepository followingRepository;
    private final OwnerStatusDefinitionRepository statusDefinitionRepository;
    private final OwnerAssignmentRepository assignmentRepository;
    private final OrganizationContext organizationContext;
    private final OwnerAuthorizationService ownerAuthorizationService;
    private final ApplicationEventPublisher eventPublisher;

    public OwnerService(OwnerRepository ownerRepository, OwnerQueryRepository queryRepository,
                        OwnerParcelRepository parcelRepository, OwnerActivityRepository activityRepository,
                        OwnerFollowingRepository followingRepository,
                        OwnerStatusDefinitionRepository statusDefinitionRepository,
                        OwnerAssignmentRepository assignmentRepository,
                        OrganizationContext organizationContext, OwnerAuthorizationService ownerAuthorizationService,
                        ApplicationEventPublisher eventPublisher) {
        this.ownerRepository = ownerRepository;
        this.queryRepository = queryRepository;
        this.parcelRepository = parcelRepository;
        this.activityRepository = activityRepository;
        this.followingRepository = followingRepository;
        this.statusDefinitionRepository = statusDefinitionRepository;
        this.assignmentRepository = assignmentRepository;
        this.organizationContext = organizationContext;
        this.ownerAuthorizationService = ownerAuthorizationService;
        this.eventPublisher = eventPublisher;
    }

    // ===================== Search =====================

    @Transactional(readOnly = true)
    public PagedResponse<OwnerListItem> search(String search, int page, int size) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Pagination.PageRequest pagination = Pagination.normalize(page, size);
        String assignedSubject = ownerAuthorizationService.assignedSubjectRestriction().orElse(null);
        long total = queryRepository.countOwners(orgId, search, assignedSubject);
        List<OwnerListItem> owners = queryRepository.findOwners(orgId, search, pagination.page(), pagination.size(), assignedSubject);
        return new PagedResponse<>(owners, pagination.page(), pagination.size(), total, Pagination.totalPages(total, pagination.size()));
    }

    // ===================== Get owner =====================

    @Transactional(readOnly = true)
    public OwnerDetails get(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanRead(owner);
        long parcelCount = parcelRepository.parcelCount(orgId, ownerId);
        boolean sensitive = ownerAuthorizationService.canReadSensitiveOwnerFields();
        List<OwnerDtos.Activity> activities = sensitive
            ? activityRepository.findActivities(orgId, ownerId)
            : List.of();
        List<String> followings = sensitive
            ? followingRepository.findFollowings(ownerId)
            : List.of();
        return new OwnerDetails(
            owner.getId(), owner.getDisplayName(),
            sensitive ? owner.getPersonalOrRegistryCode() : null,
            owner.getStatus(), parcelCount, owner.getUpdatedAt(),
            sensitive ? owner.getEmail() : null,
            sensitive ? owner.getPhone() : null,
            owner.getAssignedToSubject(), owner.getOwnerType(),
            sensitive ? owner.getAddress() : null,
            sensitive ? owner.getInfo() : null,
            owner.getStatusSetAt(), owner.getVersion(),
            parcelRepository.findParcels(orgId, ownerId), activities, followings);
    }

    // ===================== Create owner =====================

    @Transactional
    public OwnerDetails create(CreateOwnerRequest req) {
        UUID orgId = organizationContext.requiredOrganizationId();
        if (ownerRepository.findByOrganizationIdAndPersonalOrRegistryCode(orgId, req.personalOrRegistryCode()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Owner with this code already exists");
        }
        Owner owner = new Owner(null, orgId, req.displayName(), req.personalOrRegistryCode(),
            req.email(), req.phone(), OwnerStatus.NEW, null);
        if (req.ownerType() != null) owner.setOwnerType(req.ownerType());
        ownerRepository.saveAndFlush(owner);
        followingRepository.addFollowing(owner.getId(), organizationContext.subject());
        activityRepository.addActivity(orgId, owner.getId(), "NOTE", "Omanik lisatud.", organizationContext.subject());
        return get(owner.getId());
    }

    // ===================== Update owner =====================

    @Transactional
    public OwnerDetails update(UUID ownerId, UpdateOwnerRequest req) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanMutate(owner);
        assertFreshVersion(req.version(), owner);
        if (req.displayName() != null) owner.setDisplayName(req.displayName());
        if (req.phone() != null) owner.setPhone(req.phone());
        if (req.email() != null) owner.setEmail(req.email());
        if (req.ownerType() != null) owner.setOwnerType(req.ownerType());
        if (req.address() != null) owner.setAddress(req.address());
        if (req.info() != null) owner.setInfo(req.info());
        ownerRepository.save(owner);
        return get(ownerId);
    }

    // ===================== Change status =====================

    @Transactional
    public OwnerDetails changeStatus(UUID ownerId, ChangeStatusRequest req) {
        UUID orgId = organizationContext.requiredOrganizationId();
        if (!statusDefinitionRepository.statusDefinitionExists(orgId, req.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown owner status");
        }
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanMutate(owner);
        assertFreshVersion(req.version(), owner);
        String previousStatus = owner.getStatus();
        if (OwnerStatus.DECEASED.equals(previousStatus) && !OwnerStatus.DECEASED.equals(req.status())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Surnud omaniku staatust ei saa tavapärase müügitöö kaudu muuta; halda pärimisjuhtumit või paranda ametlik teade.");
        }
        if (req.assignee() != null) ownerAuthorizationService.assertCanChangeAssignment();
        owner.changeStatus(req.status());
        if (req.assignee() != null) owner.setAssignedToSubject(req.assignee());
        ownerRepository.save(owner);
        String comment = req.comment() != null ? "\nKommentaar: " + req.comment() : "";
        String subject = organizationContext.subject();
        activityRepository.addActivity(orgId, ownerId, "STATUS_CHANGED",
            "Staatus muudeti: " + previousStatus + " → " + req.status() + comment, subject);
        activityRepository.addUserStatusChangeRecord(orgId, subject, previousStatus, req.status());
        eventPublisher.publishEvent(new OwnerStatusChangedEvent(orgId, ownerId, req.status()));
        return get(ownerId);
    }

    @Transactional(readOnly = true)
    public List<OwnerStatusDefinition> allowedManualStatusTransitions(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanMutate(owner);
        String currentStatus = owner.getStatus();
        return statusDefinitionRepository.findStatusDefinitions(orgId).stream()
            .filter(definition -> isManualStatusAllowed(currentStatus, definition.id()))
            .toList();
    }

    static boolean isManualStatusAllowed(String currentStatus, String targetStatus) {
        if (targetStatus == null || targetStatus.equals(currentStatus)) {
            return false;
        }
        if (OwnerStatus.EVALUATION.equals(targetStatus) || OwnerStatus.WAITS_FOR_EVALUATION.equals(targetStatus)) {
            return false;
        }
        return !OwnerStatus.DECEASED.equals(currentStatus);
    }

    // ===================== Followings =====================

    @Transactional
    public void addFollowing(UUID ownerId, String userSubject) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanRead(owner);
        followingRepository.addFollowing(ownerId, userSubject != null ? userSubject : organizationContext.subject());
    }

    @Transactional
    public void removeFollowing(UUID ownerId) {
        Owner owner = findOwnerOrThrow(ownerId, organizationContext.requiredOrganizationId());
        ownerAuthorizationService.assertCanRead(owner);
        followingRepository.removeFollowing(ownerId, organizationContext.subject());
    }

    // ===================== Evaluation workdesk =====================

    @Transactional(readOnly = true)
    public List<OwnerListItem> ownersAwaitingEvaluation() {
        return queryRepository.findOwnersAwaitingEvaluation(organizationContext.requiredOrganizationId());
    }

    // ===================== Admin workdesk =====================

    @Transactional(readOnly = true)
    public List<OwnerListItem> adminSearch(AdminSearchRequest req) {
        return queryRepository.adminSearch(organizationContext.requiredOrganizationId(), req);
    }

    @Transactional(readOnly = true)
    public AdminPrepData getAdminPrepData() {
        return queryRepository.getAdminPrepData(organizationContext.requiredOrganizationId());
    }

    @Transactional
    public void assignWork(AssignWorkRequest req) {
        UUID orgId = organizationContext.requiredOrganizationId();
        if (req.assignee() == null || req.assignee().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee is required");
        }
        if (req.owners() == null || req.owners().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one owner is required");
        }
        Set<UUID> requestedOwnerIds = new LinkedHashSet<>(req.owners());
        Set<UUID> existingOwnerIds = new LinkedHashSet<>(assignmentRepository.findExistingOwnerIds(orgId, List.copyOf(requestedOwnerIds)));
        if (!existingOwnerIds.equals(requestedOwnerIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request contains owners outside the organization");
        }
        List<UUID> ownerIds = List.copyOf(existingOwnerIds);
        assignmentRepository.setCallerForOwners(orgId, req.assignee(), ownerIds);
        assignmentRepository.markDataSignalsAssigned(orgId, ownerIds);
        if (!req.reassign()) {
            assignmentRepository.setStatusForOwners(orgId, OwnerStatus.ASSIGNED, ownerIds);
        }
        String subject = organizationContext.subject();
        for (UUID ownerId : ownerIds) {
            activityRepository.addActivity(orgId, ownerId, "ASSIGNED",
                "Määratud kasutajale: " + req.assignee(), subject);
            followingRepository.addFollowing(ownerId, req.assignee());
        }
    }

    // ===================== Owner status definitions =====================

    @Transactional(readOnly = true)
    public List<OwnerStatusDefinition> getStatusDefinitions() {
        return statusDefinitionRepository.findStatusDefinitions(organizationContext.requiredOrganizationId());
    }

    @Transactional
    public void saveStatusDefinition(SaveOwnerStatusRequest req) {
        statusDefinitionRepository.upsertStatusDefinition(organizationContext.requiredOrganizationId(), req);
    }

    @Transactional
    public void deleteStatusDefinition(String id) {
        statusDefinitionRepository.deleteStatusDefinition(organizationContext.requiredOrganizationId(), id);
    }

    // ===================== Add note =====================

    @Transactional
    public void addNote(UUID ownerId, String content) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanMutate(owner);
        activityRepository.addActivity(orgId, ownerId, "NOTE", content, organizationContext.subject());
    }

    // ===================== Mark parcels =====================

    @Transactional
    public void markParcels(UUID ownerId, java.util.List<String> cadastralCodes) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = findOwnerOrThrow(ownerId, orgId);
        ownerAuthorizationService.assertCanMutate(owner);
        // Unmark all, then mark selected
        parcelRepository.unmarkAllParcels(orgId, ownerId);
        if (cadastralCodes != null && !cadastralCodes.isEmpty()) {
            parcelRepository.markParcels(orgId, ownerId, cadastralCodes);
        }
    }

    // ===================== Helpers =====================

    private Owner findOwnerOrThrow(UUID ownerId, UUID orgId) {
        return ownerRepository.findByIdAndOrganizationId(ownerId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found"));
    }

    private void assertFreshVersion(Long expectedVersion, Owner owner) {
        if (expectedVersion != null && !expectedVersion.equals(owner.getVersion())) {
            throw ApiExceptionHandler.staleEntity();
        }
    }

    public record OwnerStatusChangedEvent(UUID organizationId, UUID ownerId, String status) { }
}
