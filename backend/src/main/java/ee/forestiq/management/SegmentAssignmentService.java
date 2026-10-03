package ee.forestiq.management;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.identity.OrganizationRole;
import ee.forestiq.identity.OrganizationUser;
import ee.forestiq.management.SalesManagementDtos.ApplyAssignmentsRequest;
import ee.forestiq.management.SalesManagementDtos.AssignmentCandidate;
import ee.forestiq.management.SalesManagementDtos.AssignmentDecision;
import ee.forestiq.management.SalesManagementDtos.AssignmentPreview;
import ee.forestiq.management.SalesManagementDtos.AssignmentResult;
import ee.forestiq.management.SalesManagementDtos.AssignmentResultItem;
import ee.forestiq.management.SalesManagementDtos.AssignmentSuggestion;
import ee.forestiq.management.SalesManagementDtos.SaveSegmentRequest;
import ee.forestiq.management.SalesManagementDtos.SegmentMember;
import ee.forestiq.management.SalesManagementDtos.SegmentRules;
import ee.forestiq.management.SalesManagementDtos.SegmentRunResult;
import ee.forestiq.management.SalesManagementDtos.SegmentSummary;
import ee.forestiq.management.readmodel.ManagementReadModelPort;
import ee.forestiq.management.readmodel.ManagementReadModelPort.AssignmentLoad;
import ee.forestiq.management.readmodel.ManagementReadModelPort.SegmentSlice;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.ManagementAssignment;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.ManagementAssignmentOutcome;
import ee.forestiq.security.OrganizationContext;

@Service
public class SegmentAssignmentService {
    private static final Set<OrganizationRole> ASSIGNMENT_ROLES = Set.of(
        OrganizationRole.CALLER, OrganizationRole.CRM_MANAGER, OrganizationRole.ORG_ADMIN);
    private static final Set<String> CONTACT_STATES = Set.of("HAS_CONTACT", "MISSING_CONTACT");
    private static final Set<String> SIGNALS = Set.of("FRESH_FOREST_NOTIFICATION", "STALE_REGISTRY_DATA", "RESTRICTION_LABEL", "MISSING_CONTACT");
    private static final int SEGMENT_PREVIEW_LIMIT = 200;
    private static final int ASSIGNMENT_PREVIEW_LIMIT = 500;

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerCollaborationPort ownerCollaboration;
    private final OrganizationIdentityDirectory identityDirectory;
    private final ManagementReadModelPort readModel;
    private final ObjectMapper objectMapper;

    public SegmentAssignmentService(DSLContext dsl, OrganizationContext orgCtx,
                                    OwnerCollaborationPort ownerCollaboration,
                                    OrganizationIdentityDirectory identityDirectory,
                                    ManagementReadModelPort readModel,
                                    ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerCollaboration = ownerCollaboration;
        this.identityDirectory = identityDirectory;
        this.readModel = readModel;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SegmentSummary> listSegments() {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        return dsl.fetch("""
            SELECT id, name, description, rules, recommended_workflow, active, last_calculated_at,
                   last_calculated_count, created_by_subject, created_at, updated_by_subject, updated_at
              FROM sales_target_segment
             WHERE organization_id = ?
             ORDER BY active DESC, updated_at DESC, id
            """, orgId).map(this::segment);
    }

    @Transactional
    public SegmentSummary createSegment(SaveSegmentRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        validateRules(request.rules());
        UUID id = UUID.randomUUID();
        String json = json(request.rules());
        dsl.execute("""
            INSERT INTO sales_target_segment
                (id, organization_id, name, description, rules, recommended_workflow, active,
                 created_by_subject, updated_by_subject)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
            """, id, orgId, request.name().trim(), trim(request.description()), json,
            trim(request.recommendedWorkflow()), request.active(), orgCtx.subject(), orgCtx.subject());
        addSegmentEvent(orgId, id, "CREATED", Map.of("name", request.name().trim()));
        return requireSegment(orgId, id);
    }

    @Transactional
    public SegmentSummary updateSegment(UUID id, SaveSegmentRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        requireSegment(orgId, id);
        validateRules(request.rules());
        dsl.execute("""
            UPDATE sales_target_segment
               SET name = ?, description = ?, rules = CAST(? AS jsonb), recommended_workflow = ?, active = ?,
                   updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, request.name().trim(), trim(request.description()), json(request.rules()),
            trim(request.recommendedWorkflow()), request.active(), orgCtx.subject(), orgId, id);
        addSegmentEvent(orgId, id, "UPDATED", Map.of("name", request.name().trim()));
        return requireSegment(orgId, id);
    }

    @Transactional
    public SegmentRunResult runSegment(UUID id) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        SegmentSummary segment = requireSegment(orgId, id);
        QueryResult result = queryMembers(orgId, segment.rules(), SEGMENT_PREVIEW_LIMIT);
        Instant calculatedAt = Instant.now();
        dsl.execute("""
            UPDATE sales_target_segment
               SET last_calculated_at = ?, last_calculated_count = ?, updated_by_subject = ?, updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, calculatedAt, result.total(), orgCtx.subject(), orgId, id);
        addSegmentEvent(orgId, id, "CALCULATED", Map.of("count", result.total()));
        return new SegmentRunResult(requireSegment(orgId, id), result.total(), result.members(), calculatedAt);
    }

    @Transactional
    public AssignmentPreview assignmentPreview(UUID segmentId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        SegmentSummary segment = requireSegment(orgId, segmentId);
        QueryResult result = queryMembers(orgId, segment.rules(), ASSIGNMENT_PREVIEW_LIMIT);
        List<AssignmentCandidate> candidates = assignmentCandidates(orgId);
        if (candidates.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Organisatsioonis pole sobiva rolliga aktiivseid töö vastuvõtjaid.");

        List<AssignmentCandidate> ordered = candidates.stream().sorted(Comparator
            .comparing(AssignmentCandidate::loadScore)
            .thenComparing(AssignmentCandidate::subject)).toList();
        Map<String, BigDecimal> projected = new HashMap<>();
        ordered.forEach(c -> projected.put(c.subject(), c.loadScore()));
        List<AssignmentSuggestion> suggestions = new ArrayList<>();
        for (SegmentMember member : result.members()) {
            AssignmentCandidate chosen = ordered.stream()
                .min(Comparator.comparing(c -> projected.get(c.subject())))
                .orElseThrow();
            String explanation = "Madalaim projitseeritud koormusskoor " + projected.get(chosen.subject()).setScale(2, RoundingMode.HALF_UP)
                + "; avatud töid " + chosen.openWork() + ", üle tähtaja " + chosen.overdueWork()
                + ", 7 päeva sees " + chosen.dueNextSevenDays() + ".";
            suggestions.add(new AssignmentSuggestion(member.ownerId(), member.ownerName(), member.assignedToSubject(), chosen.subject(), explanation));
            projected.put(chosen.subject(), projected.get(chosen.subject()).add(BigDecimal.ONE));
        }
        String rationale = "Soovitus järjestab kasutajad koormusskoori järgi: avatud töö + 3×tähtajaületus + lähiaja töö + 0,5×hiljutine määramine + 0,05×määratud omanikud. Juht kinnitab või muudab tulemuse käsitsi.";
        addSegmentEvent(orgId, segmentId, "ASSIGNMENT_PREVIEWED", Map.of("candidateCount", candidates.size(), "ownerCount", result.total()));
        return new AssignmentPreview(segmentId, result.total(), candidates, suggestions, "LOWEST_PROJECTED_LOAD", rationale, Instant.now());
    }

    @Transactional
    public AssignmentResult applyAssignments(ApplyAssignmentsRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        if (request.assignments().size() > 1000) throw new ResponseStatusException(BAD_REQUEST, "Ühe massmääramise ülempiir on 1000 omanikku.");
        if (request.segmentId() != null) requireSegment(orgId, request.segmentId());

        Map<String, OrganizationUser> eligible = eligibleUsers(orgId);
        Set<UUID> seen = new LinkedHashSet<>();
        List<ManagementAssignment> assignments = new ArrayList<>();
        for (AssignmentDecision decision : request.assignments()) {
            if (!seen.add(decision.ownerId())) throw new ResponseStatusException(BAD_REQUEST, "Sama omanik esineb määramisotsuses mitu korda.");
            OrganizationUser target = eligible.get(decision.toSubject());
            if (target == null) throw new ResponseStatusException(BAD_REQUEST, "Kasutaja ei ole aktiivne või tal puudub töö vastuvõtmiseks sobiv roll: " + decision.toSubject());
            assignments.add(new ManagementAssignment(decision.ownerId(), decision.toSubject()));
        }

        List<ManagementAssignmentOutcome> changes = ownerCollaboration.applyManagementAssignments(
            assignments, request.rationale().trim());

        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        dsl.execute("""
            INSERT INTO management_assignment_run
                (id, organization_id, segment_id, assignment_rule, rationale, item_count, created_by_subject, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, runId, orgId, request.segmentId(), request.assignmentRule().trim(), request.rationale().trim(),
            changes.size(), orgCtx.subject(), createdAt);

        int assignedCount = 0;
        int reassignedCount = 0;
        int unchangedCount = 0;
        List<AssignmentResultItem> items = new ArrayList<>();
        for (ManagementAssignmentOutcome change : changes) {
            if (!change.changed()) {
                unchangedCount++;
                items.add(new AssignmentResultItem(change.ownerId(), change.ownerName(), change.fromSubject(), change.toSubject(), "UNCHANGED"));
                continue;
            }
            String itemRationale = "Juhi kinnitatud massmääramine: " + request.rationale().trim();
            dsl.execute("""
                INSERT INTO management_assignment_item
                    (run_id, organization_id, owner_id, from_subject, to_subject, rationale, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, orgId, change.ownerId(), change.fromSubject(), change.toSubject(), itemRationale, createdAt);
            if (change.fromSubject() == null) assignedCount++; else reassignedCount++;
            items.add(new AssignmentResultItem(change.ownerId(), change.ownerName(), change.fromSubject(), change.toSubject(),
                change.fromSubject() == null ? "ASSIGNED" : "REASSIGNED"));
        }
        if (request.segmentId() != null) {
            addSegmentEvent(orgId, request.segmentId(), "ASSIGNED", Map.of("runId", runId.toString(), "count", assignedCount + reassignedCount, "unchanged", unchangedCount));
        }
        return new AssignmentResult(runId, assignedCount, reassignedCount, unchangedCount, 0, List.copyOf(items), createdAt);
    }

    @Transactional(readOnly = true)
    public List<AssignmentCandidate> assignmentCandidates() {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireManager();
        return assignmentCandidates(orgId);
    }

    private List<AssignmentCandidate> assignmentCandidates(UUID orgId) {
        List<AssignmentCandidate> result = new ArrayList<>();
        for (OrganizationUser user : eligibleUsers(orgId).values()) {
            AssignmentLoad load = readModel.assignmentLoad(orgId, user.subject());
            BigDecimal score = BigDecimal.valueOf(load.openWork())
                .add(BigDecimal.valueOf(load.overdueWork()).multiply(BigDecimal.valueOf(3)))
                .add(BigDecimal.valueOf(load.dueNextSevenDays()))
                .add(BigDecimal.valueOf(load.recentlyAssigned()).multiply(new BigDecimal("0.5")))
                .add(BigDecimal.valueOf(load.assignedOwners()).multiply(new BigDecimal("0.05")))
                .setScale(2, RoundingMode.HALF_UP);
            String explanation = "Avatud " + load.openWork() + ", üle tähtaja " + load.overdueWork()
                + ", 7 päeva sees " + load.dueNextSevenDays()
                + ", viimase 7 päeva uued määramised " + load.recentlyAssigned()
                + ", määratud omanikud " + load.assignedOwners() + ".";
            result.add(new AssignmentCandidate(user.subject(), user.username(), user.displayName(),
                user.roles().stream().map(OrganizationRole::name).sorted().toList(), load.assignedOwners(),
                load.openWork(), load.overdueWork(), load.dueNextSevenDays(), load.recentlyAssigned(),
                score, explanation));
        }
        return result.stream().sorted(Comparator.comparing(AssignmentCandidate::loadScore)
            .thenComparing(AssignmentCandidate::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    private Map<String, OrganizationUser> eligibleUsers(UUID orgId) {
        Map<String, OrganizationUser> result = new LinkedHashMap<>();
        identityDirectory.listActiveOrganizationUsers(orgId).stream()
            .filter(user -> user.roles().stream().anyMatch(ASSIGNMENT_ROLES::contains))
            .forEach(user -> result.put(user.subject(), user));
        return result;
    }

    private QueryResult queryMembers(UUID orgId, SegmentRules rules, int limit) {
        validateRules(rules);
        SegmentSlice slice = readModel.segmentMembers(orgId, rules, limit);
        List<SegmentMember> members = slice.rows().stream()
            .map(row -> new SegmentMember(
                row.ownerId(), row.ownerName(), row.status(), row.assignedToSubject(),
                row.forestArea(), row.county(), row.hasContact()))
            .toList();
        return new QueryResult(slice.total(), members);
    }

    private SegmentSummary requireSegment(UUID orgId, UUID id) {
        Record row = dsl.fetchOne("""
            SELECT id, name, description, rules, recommended_workflow, active, last_calculated_at,
                   last_calculated_count, created_by_subject, created_at, updated_by_subject, updated_at
              FROM sales_target_segment WHERE organization_id = ? AND id = ?
            """, orgId, id);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Sihtrühma ei leitud.");
        return segment(row);
    }

    private SegmentSummary segment(Record row) {
        SegmentRules rules;
        try { rules = objectMapper.readValue(row.get("rules", JSONB.class).data(), SegmentRules.class); }
        catch (Exception exception) { throw new IllegalStateException("Sihtrühma reegleid ei saa lugeda.", exception); }
        return new SegmentSummary(row.get("id", UUID.class), row.get("name", String.class), row.get("description", String.class), rules,
            row.get("recommended_workflow", String.class), Boolean.TRUE.equals(row.get("active", Boolean.class)),
            instant(row, "last_calculated_at"), row.get("last_calculated_count", Integer.class),
            row.get("created_by_subject", String.class), instant(row, "created_at"),
            row.get("updated_by_subject", String.class), instant(row, "updated_at"));
    }

    private void validateRules(SegmentRules rules) {
        if (rules == null) throw new ResponseStatusException(BAD_REQUEST, "Sihtrühma reeglid on kohustuslikud.");
        if (rules.minForestAreaHectares() != null && rules.minForestAreaHectares().signum() < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Metsamaa pindala ei tohi olla negatiivne.");
        }
        String contact = normalizeOptional(rules.contactState());
        if (contact != null && !CONTACT_STATES.contains(contact)) throw new ResponseStatusException(BAD_REQUEST, "Tundmatu kontaktifilter.");
        String signal = normalizeOptional(rules.signalCode());
        if (signal != null && !SIGNALS.contains(signal)) throw new ResponseStatusException(BAD_REQUEST, "Tundmatu andmesignaal.");
        if (rules.statuses() != null && rules.statuses().size() > 30) throw new ResponseStatusException(BAD_REQUEST, "Liiga palju staatusfiltreid.");
    }

    private void requireManager() {
        ownerCollaboration.requireAssignmentManagementAccess();
    }

    private void addSegmentEvent(UUID orgId, UUID segmentId, String type, Map<String, ?> payload) {
        dsl.execute("""
            INSERT INTO sales_target_segment_event (organization_id, segment_id, event_type, actor_subject, payload)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            """, orgId, segmentId, type, orgCtx.subject(), json(payload));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("JSON serialiseerimine ebaõnnestus.", exception); }
    }

    private static long number(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0 : value.longValue();
    }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String normalizeOptional(String value) { String v = trim(value); return v == null ? null : v.toUpperCase(); }
    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record QueryResult(int total, List<SegmentMember> members) {}
}
