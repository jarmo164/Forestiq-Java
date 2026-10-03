package ee.forestiq.inheritance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.inheritance.InheritanceRepository.CaseSnapshot;
import ee.forestiq.inheritance.InheritanceRepository.OwnerSnapshot;
import ee.forestiq.owner.OwnerService.OwnerStatusChangedEvent;
import ee.forestiq.owner.OwnerStatus;
import ee.forestiq.security.OrganizationContext;

@Service
public class InheritanceService {

    private static final String SYSTEM_SUBJECT = "system:inheritance-source";
    private static final List<String> CASE_STATUSES = List.of(
        "NOTICE_RECEIVED", "HEIR_DISCOVERY", "HEIRS_CONTACTED", "NEGOTIATION", "SETTLED", "CLOSED", "NOT_RELEVANT");
    private static final List<String> HEIR_CONTACT_STATUSES = List.of(
        "UNVERIFIED", "READY_TO_CONTACT", "CONTACTED", "INTERESTED", "NOT_INTERESTED", "NOT_REACHABLE");
    private static final Map<String, List<String>> ALLOWED_NEXT_CASE_STATUSES = Map.of(
        "NOTICE_RECEIVED", List.of("HEIR_DISCOVERY", "NOT_RELEVANT", "CLOSED"),
        "HEIR_DISCOVERY", List.of("HEIRS_CONTACTED", "NOT_RELEVANT", "CLOSED"),
        "HEIRS_CONTACTED", List.of("NEGOTIATION", "NOT_RELEVANT", "CLOSED"),
        "NEGOTIATION", List.of("SETTLED", "NOT_RELEVANT", "CLOSED"),
        "SETTLED", List.of(),
        "CLOSED", List.of(),
        "NOT_RELEVANT", List.of());

    private final OrganizationContext organizationContext;
    private final ApplicationEventPublisher eventPublisher;
    private final InheritanceOfficialNoticeClient officialNoticeClient;
    private final InheritanceRepository repository;

    @Autowired
    public InheritanceService(OrganizationContext organizationContext, ApplicationEventPublisher eventPublisher,
                              InheritanceOfficialNoticeClient officialNoticeClient, InheritanceRepository repository) {
        this.organizationContext = organizationContext;
        this.eventPublisher = eventPublisher;
        this.officialNoticeClient = officialNoticeClient;
        this.repository = repository;
    }

    /** Compatibility constructor retained for H2 characterization tests. */
    public InheritanceService(DSLContext dsl, OrganizationContext organizationContext,
                              ApplicationEventPublisher eventPublisher, InheritanceOfficialNoticeClient officialNoticeClient) {
        this(organizationContext, eventPublisher, officialNoticeClient, new InheritanceRepository(dsl));
    }

    @Transactional(readOnly = true)
    public InheritanceWorkspace workspace(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        repository.ownerSnapshot(orgId, ownerId);
        return new InheritanceWorkspace(ownerId, repository.latestCaseDetails(orgId, ownerId));
    }

    @Transactional(readOnly = true)
    public InheritanceCasePage searchCases(String query, String status, String assignedTo,
                                           boolean overdue, int page, int size) {
        UUID orgId = organizationContext.requiredOrganizationId();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String visibleSubject = canViewAllCases() ? null : organizationContext.subject();
        return repository.searchCases(orgId, query, status, assignedTo, overdue, visibleSubject, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public InheritanceCaseWorkspace caseWorkspace(UUID caseId) {
        InheritanceCaseWorkspace workspace = repository.caseWorkspace(organizationContext.requiredOrganizationId(), caseId);
        if (!canViewAllCases() && !organizationContext.subject().equals(workspace.assignedToSubject())) {
            throw new org.springframework.security.access.AccessDeniedException("Pärimisjuhtum ei ole sellele kasutajale määratud.");
        }
        return workspace;
    }

    @Transactional
    public InheritanceCaseWorkspace assignCase(UUID caseId, String assignedToSubject) {
        UUID orgId = organizationContext.requiredOrganizationId();
        InheritanceCaseWorkspace current = repository.caseWorkspace(orgId, caseId);
        repository.assignCase(orgId, caseId, assignedToSubject);
        String description = assignedToSubject == null || assignedToSubject.isBlank()
            ? "Pärimisjuhtumi töömäärang eemaldati."
            : "Pärimisjuhtum määrati kasutajale " + assignedToSubject.trim() + ".";
        repository.addEvent(orgId, caseId, "ASSIGNED", description,
            Map.of("assignedTo", assignedToSubject == null ? "" : assignedToSubject.trim()),
            organizationContext.subject());
        return repository.caseWorkspace(orgId, caseId);
    }

    private boolean canViewAllCases() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        return authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
            .anyMatch(role -> List.of("ROLE_ORG_ADMIN", "ROLE_CRM_MANAGER", "ROLE_EVALUATOR").contains(role));
    }

    @Transactional
    public OfficialNoticeCheckResult checkOfficialNotices(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        return checkOfficialNotices(orgId, repository.ownerSnapshot(orgId, ownerId), organizationContext.subject());
    }

    /** Used only by the explicitly enabled scheduled worker. */
    @Transactional
    public int refreshDueOfficialNotices() {
        if (!officialNoticeClient.isConfigured()) return 0;
        int batchSize = Math.min(Math.max(1, officialNoticeClient.autoRefreshBatchSize()), 500);
        OffsetDateTime staleBefore = dbTime(Instant.now().minus(Duration.ofDays(1)));
        List<OwnerSnapshot> owners = repository.findOwnersDueForNoticeCheck(staleBefore, batchSize);
        for (OwnerSnapshot owner : owners) checkOfficialNotices(owner.organizationId(), owner, SYSTEM_SUBJECT);
        return owners.size();
    }

    @Transactional
    public InheritanceCaseDetails start(UUID ownerId, StartInheritanceCaseRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        OwnerSnapshot owner = repository.ownerSnapshot(orgId, ownerId);
        if (!OwnerStatus.DECEASED.equals(owner.status())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Pärimisjuhtumi saab avada pärast ametliku teate kontrolli või kinnitatud surnud-staatust.");
        }
        return startCase(orgId, ownerId, request, organizationContext.subject());
    }

    @Transactional
    public InheritanceCaseDetails importOfficialNotice(UUID ownerId, String noticeNumber) {
        UUID orgId = organizationContext.requiredOrganizationId();
        OwnerSnapshot owner = repository.ownerSnapshot(orgId, ownerId);
        Map<String, Object> payload = officialNoticeClient.fetchByNoticeNumber(noticeNumber);
        return importOfficialPayload(orgId, owner, payload, organizationContext.subject());
    }

    @Transactional
    public InheritanceCaseDetails changeStatus(UUID caseId, ChangeInheritanceStatusRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        assertCaseVisible(caseId);
        validateCaseStatus(request.status());
        CaseSnapshot current = repository.requireCase(orgId, caseId);
        validateCaseTransition(current.status(), request.status());
        repository.updateCaseStatus(orgId, caseId, request.status(), isTerminal(request.status()));
        String comment = blankToNull(request.comment());
        String description = "Pärimisjuhtumi staatus: " + current.status() + " → " + request.status()
            + (comment == null ? "" : ". Kommentaar: " + comment);
        repository.addEvent(orgId, caseId, "STATUS_CHANGED", description,
            Map.of("from", current.status(), "to", request.status()), subject);
        repository.addOwnerActivity(orgId, current.ownerId(), "SYSTEM", description, subject);
        return repository.details(orgId, caseId);
    }

    @Transactional
    public InheritanceCaseDetails addHeir(UUID caseId, SaveInheritanceHeirRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        repository.requireCase(orgId, caseId);
        SaveInheritanceHeirRequest normalized = normalizedHeir(request);
        UUID heirId = repository.insertHeir(orgId, caseId, normalized);
        repository.addEvent(orgId, caseId, "HEIR_ADDED", "Pärija lisati: " + normalized.displayName(),
            Map.of("heirId", heirId.toString()), subject);
        return repository.details(orgId, caseId);
    }

    @Transactional
    public InheritanceCaseDetails updateHeir(UUID caseId, UUID heirId, SaveInheritanceHeirRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        assertCaseVisible(caseId);
        repository.requireCase(orgId, caseId);
        SaveInheritanceHeirRequest normalized = normalizedHeir(request);
        int changed = repository.updateHeir(orgId, caseId, heirId, normalized);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pärijat ei leitud.");
        repository.addEvent(orgId, caseId, "HEIR_UPDATED", "Pärija andmeid või kontaktistaatust muudeti.",
            Map.of("heirId", heirId.toString()), subject);
        return repository.details(orgId, caseId);
    }

    @Transactional
    public InheritanceCaseDetails addNote(UUID caseId, AddInheritanceNoteRequest request) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        assertCaseVisible(caseId);
        repository.requireCase(orgId, caseId);
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tegevuse kirjeldus on kohustuslik.");
        }
        repository.addEvent(orgId, caseId, "NOTE", request.content().trim(), Map.of(), subject);
        return repository.details(orgId, caseId);
    }

    private OfficialNoticeCheckResult checkOfficialNotices(UUID orgId, OwnerSnapshot owner, String subject) {
        if (!officialNoticeClient.isConfigured()) {
            return new OfficialNoticeCheckResult(false, false,
                "Pärimisteadete serveritevaheline liides ei ole seadistatud.", null);
        }
        if (!isPersonalCode(owner.personalCode())) {
            return new OfficialNoticeCheckResult(true, false,
                "Omanikul puudub kontrollitav 11-kohaline isikukood.", null);
        }
        try {
            List<Map<String, Object>> notices = officialNoticeClient.findByPersonalCode(owner.personalCode());
            if (notices.isEmpty()) {
                repository.recordNoticeCheck(orgId, owner.id(), null, null);
                return new OfficialNoticeCheckResult(true, false, "Ametlikke pärimisteateid ei leitud.", null);
            }
            InheritanceCaseDetails inheritanceCase = importOfficialPayload(orgId, owner, notices.getFirst(), subject);
            repository.recordNoticeCheck(orgId, owner.id(), inheritanceCase.sourceNoticeNumber(), null);
            return new OfficialNoticeCheckResult(true, true,
                "Ametlik pärimisteade seoti omaniku ja pärimisjuhtumiga.", inheritanceCase);
        } catch (Exception exception) {
            repository.recordNoticeCheck(orgId, owner.id(), null, safeError(exception));
            return new OfficialNoticeCheckResult(false, false,
                "Pärimisteadete allikast kontrollimine ebaõnnestus.", null);
        }
    }

    private InheritanceCaseDetails importOfficialPayload(UUID orgId, OwnerSnapshot owner,
                                                         Map<String, Object> payload, String subject) {
        String noticeNumber = text(payload, "notice_number");
        String personalCode = text(payload, "personal_code");
        if (noticeNumber == null || noticeNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ametlikul teatel puudub teate number.");
        }
        if (!isPersonalCode(personalCode) || !personalCode.equals(owner.personalCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Ametlik teade ei vasta omaniku isikukoodile.");
        }

        markOwnerDeceased(orgId, owner, noticeNumber, subject);
        InheritanceCaseDetails existing = repository.findByNotice(orgId, owner.id(), noticeNumber);
        if (existing != null) return existing;

        InheritanceCaseDetails created = startCase(orgId, owner.id(), new StartInheritanceCaseRequest(
            noticeNumber, text(payload, "source_url"), date(payload, "announcement_date"), date(payload, "death_date"),
            date(payload, "certification_deadline"), text(payload, "notary_name"), text(payload, "notary_phone")), subject);
        Object rawHeirs = payload.get("heirs");
        if (rawHeirs instanceof List<?> heirs) {
            for (Object item : heirs) {
                if (!(item instanceof Map<?, ?> rawHeir)) continue;
                Map<String, Object> heir = new java.util.LinkedHashMap<>();
                rawHeir.forEach((key, value) -> heir.put(String.valueOf(key), value));
                String name = text(heir, "name");
                if (name == null || name.isBlank()) continue;
                SaveInheritanceHeirRequest normalized = normalizedHeir(new SaveInheritanceHeirRequest(
                    name, text(heir, "personal_code"), text(heir, "registry_code"), text(heir, "inheritance_share"),
                    null, null, null, "UNVERIFIED", null, "OFFICIAL_NOTICE"));
                UUID heirId = repository.insertHeir(orgId, created.id(), normalized);
                repository.addEvent(orgId, created.id(), "HEIR_ADDED", "Pärija lisati: " + normalized.displayName(),
                    Map.of("heirId", heirId.toString()), subject);
            }
        }
        repository.addEvent(orgId, created.id(), "OFFICIAL_NOTICE_IMPORTED",
            "Ametlik pärimisteade imporditi serveritevahelise liidese kaudu.",
            Map.of("noticeNumber", noticeNumber), subject);
        return repository.details(orgId, created.id());
    }

    @Transactional
    InheritanceCaseDetails importNormalizedNotice(UUID orgId, UUID ownerId, Map<String, Object> payload,
                                                  String subject) {
        return importOfficialPayload(orgId, repository.ownerSnapshot(orgId, ownerId), payload, subject);
    }

    private void assertCaseVisible(UUID caseId) {
        if (canViewAllCases()) return;
        InheritanceCaseWorkspace workspace = repository.caseWorkspace(
            organizationContext.requiredOrganizationId(), caseId);
        if (!organizationContext.subject().equals(workspace.assignedToSubject())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Pärimisjuhtum ei ole sellele kasutajale määratud.");
        }
    }

    private InheritanceCaseDetails startCase(UUID orgId, UUID ownerId, StartInheritanceCaseRequest request, String subject) {
        if (repository.hasOpenCase(orgId, ownerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Omanikul on juba lõpetamata pärimisjuhtum.");
        }
        UUID caseId = repository.insertCase(orgId, ownerId, request, subject);
        repository.addEvent(orgId, caseId, "CASE_STARTED",
            "Pärimisjuhtum algatati ametliku teate või käsitsi kinnitatud info alusel.",
            Map.of("status", "NOTICE_RECEIVED"), subject);
        repository.addOwnerActivity(orgId, ownerId, "SYSTEM", "Pärimisjuhtum algatati.", subject);
        return repository.details(orgId, caseId);
    }

    private void markOwnerDeceased(UUID orgId, OwnerSnapshot owner, String noticeNumber, String subject) {
        if (OwnerStatus.DECEASED.equals(owner.status())) return;
        repository.markOwnerDeceased(orgId, owner, noticeNumber, subject);
        String description = "Ametliku pärimisteate " + noticeNumber
            + " alusel märgiti omanik surnuks ning avati pärimistöövoog.";
        repository.addOwnerActivity(orgId, owner.id(), "SYSTEM", description, subject);
        eventPublisher.publishEvent(new OwnerStatusChangedEvent(orgId, owner.id(), OwnerStatus.DECEASED));
    }

    private static SaveInheritanceHeirRequest normalizedHeir(SaveInheritanceHeirRequest request) {
        validateHeir(request);
        return new SaveInheritanceHeirRequest(
            request.displayName().trim(), blankToNull(request.personalCode()), blankToNull(request.registryCode()),
            blankToNull(request.inheritanceShare()), blankToNull(request.relationToDeceased()), blankToNull(request.phone()),
            blankToNull(request.email()), contactStatusOrDefault(request.contactStatus()), blankToNull(request.assignedToSubject()),
            blankToNull(request.source()) == null ? "MANUAL" : request.source().trim());
    }

    private static String text(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate date(Map<String, Object> source, String field) {
        String value = text(source, field);
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static boolean isTerminal(String status) {
        return List.of("SETTLED", "CLOSED", "NOT_RELEVANT").contains(status);
    }

    private static boolean isPersonalCode(String value) {
        return value != null && value.matches("\\d{11}");
    }

    private static String safeError(Exception exception) {
        String message = String.valueOf(exception.getMessage()).replaceAll("[\\r\\n]", " ");
        return exception.getClass().getSimpleName() + ": " + message.substring(0, Math.min(300, message.length()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateCaseStatus(String status) {
        if (!CASE_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tundmatu pärimisjuhtumi staatus.");
        }
    }

    private static void validateCaseTransition(String currentStatus, String nextStatus) {
        if (currentStatus.equals(nextStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pärimisjuhtum on juba valitud staatuses.");
        }
        if (!ALLOWED_NEXT_CASE_STATUSES.getOrDefault(currentStatus, List.of()).contains(nextStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Pärimisjuhtumi staatusest " + currentStatus + " ei saa liikuda staatusesse " + nextStatus + ".");
        }
    }

    private static void validateHeir(SaveInheritanceHeirRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pärija nimi on kohustuslik.");
        }
    }

    private static String contactStatusOrDefault(String status) {
        String result = blankToNull(status) == null ? "UNVERIFIED" : status.trim();
        if (!HEIR_CONTACT_STATUSES.contains(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tundmatu pärija kontaktistaatus.");
        }
        return result;
    }

    public record InheritanceWorkspace(UUID ownerId, InheritanceCaseDetails inheritanceCase) { }
    public record InheritanceCasePage(List<InheritanceCaseListItem> items, int page, int size,
                                      int totalElements, int totalPages) { }
    public record InheritanceCaseListItem(UUID id, UUID ownerId, String ownerName, String ownerCodeMasked,
        String noticeNumber, LocalDate announcementDate, LocalDate certificationDeadline, String status,
        String assignedToSubject, int heirCount, int contactedHeirCount, Instant updatedAt) { }
    public record InheritanceCaseWorkspace(UUID id, UUID ownerId, String ownerName, String ownerCodeMasked,
        String assignedToSubject, InheritanceCaseDetails inheritanceCase) { }
    public record AssignInheritanceCaseRequest(String assignedToSubject) { }
    public record OfficialNoticeCheckResult(boolean sourceAvailable, boolean noticeFound, String message,
                                            InheritanceCaseDetails inheritanceCase) { }
    public record InheritanceCaseDetails(UUID id, UUID ownerId, String sourceNoticeNumber, String sourceUrl,
        LocalDate announcementDate, LocalDate deathDate, LocalDate certificationDeadline, String notaryName,
        String notaryPhone, String status, Instant startedAt, Instant endedAt, List<InheritanceHeir> heirs,
        List<InheritanceEvent> events) { }
    public record InheritanceHeir(UUID id, String displayName, String personalCode, String registryCode,
        String inheritanceShare, String relationToDeceased, String phone, String email, String contactStatus,
        String assignedToSubject, String source) { }
    public record InheritanceEvent(UUID id, String type, String description, Instant createdAt, String createdBy) { }
    public record StartInheritanceCaseRequest(String sourceNoticeNumber, String sourceUrl, LocalDate announcementDate,
        LocalDate deathDate, LocalDate certificationDeadline, String notaryName, String notaryPhone) { }
    public record ChangeInheritanceStatusRequest(String status, String comment) { }
    public record SaveInheritanceHeirRequest(String displayName, String personalCode, String registryCode,
        String inheritanceShare, String relationToDeceased, String phone, String email, String contactStatus,
        String assignedToSubject, String source) { }
    public record AddInheritanceNoteRequest(String content) { }
}
