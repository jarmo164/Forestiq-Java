package ee.forestiq.decision;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.decision.DecisionDtos.ShareWorkbasketRequest;
import ee.forestiq.decision.DecisionDtos.Workbasket;
import ee.forestiq.decision.DecisionDtos.WorkbasketHandoff;
import ee.forestiq.decision.DecisionDtos.WorkbasketHandoffView;
import ee.forestiq.decision.DecisionDtos.WorkbasketOwner;
import ee.forestiq.decision.DecisionDtos.WorkbasketParcel;
import ee.forestiq.messaging.MessageService;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class WorkbasketHandoffService {
    private static final Set<String> SHAREABLE_ROLES = Set.of("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER", "VIEWER");

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final OrganizationIdentityDirectory identityDirectory;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    public WorkbasketHandoffService(DSLContext dsl, OrganizationContext orgCtx,
                                    OwnerAuthorizationService ownerAuthorization,
                                    OrganizationIdentityDirectory identityDirectory,
                                    MessageService messageService,
                                    ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.identityDirectory = identityDirectory;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkbasketHandoff share(UUID basketId, ShareWorkbasketRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String actor = orgCtx.subject();
        Record basket = ownedBasket(orgId, basketId, actor);
        if (request == null || (blank(request.recipientSubject()) == null) == (blank(request.recipientRole()) == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "Vali kas üks saaja või üks roll.");
        }
        if (request.dueAt() == null || !request.dueAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(BAD_REQUEST, "Üleandmise tähtaeg peab olema tulevikus.");
        }
        String recipient = blank(request.recipientSubject());
        String role = blank(request.recipientRole());
        if (recipient != null) {
            identityDirectory.requireActiveOrganizationUser(orgId, recipient);
            if (recipient.equals(actor)) throw new ResponseStatusException(BAD_REQUEST, "Töökorvi ei ole vaja iseendale üle anda.");
        } else if (!SHAREABLE_ROLES.contains(role)) {
            throw new ResponseStatusException(BAD_REQUEST, "Tundmatu saajaroll.");
        }

        UUID id = UUID.randomUUID();
        dsl.execute("""
            INSERT INTO saved_workbasket_share
              (id, organization_id, workbasket_id, recipient_subject, recipient_role, permission,
               purpose, due_at, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, orgId, basketId, recipient, role, request.permission(), request.purpose().trim(), request.dueAt(), actor);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'SHARED', ?, jsonb_build_object('handoffId', ?, 'recipientSubject', ?, 'recipientRole', ?, 'permission', ?, 'dueAt', ?))
            """, orgId, basketId, actor, id.toString(), recipient, role, request.permission(), request.dueAt().toString());
        if (recipient != null) {
            messageService.sendSystem(orgId, "system-workbasket", recipient,
                "Sulle anti üle töökorv «" + basket.get("name", String.class) + "». Ava: /workbaskets/handoffs/" + id);
        }
        return handoff(requireShare(orgId, id));
    }

    @Transactional(readOnly = true)
    public List<WorkbasketHandoff> inbox() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        List<String> roles = currentRoles();
        return dsl.fetch("""
            SELECT s.*, b.name AS workbasket_name
            FROM saved_workbasket_share s
            JOIN saved_workbasket b ON b.organization_id = s.organization_id AND b.id = s.workbasket_id
            WHERE s.organization_id = ? AND s.status <> 'CANCELLED'
              AND (s.recipient_subject = ? OR s.recipient_role = ANY(?::text[]))
            ORDER BY CASE WHEN s.status = 'PENDING' THEN 0 ELSE 1 END, s.due_at, s.created_at DESC
            LIMIT 200
            """, orgId, subject, roles.toArray(String[]::new)).map(this::handoff);
    }

    @Transactional(readOnly = true)
    public List<WorkbasketHandoff> sent() {
        UUID orgId = orgCtx.requiredOrganizationId();
        return dsl.fetch("""
            SELECT s.*, b.name AS workbasket_name
            FROM saved_workbasket_share s
            JOIN saved_workbasket b ON b.organization_id = s.organization_id AND b.id = s.workbasket_id
            WHERE s.organization_id = ? AND s.created_by_subject = ?
            ORDER BY s.created_at DESC
            LIMIT 200
            """, orgId, orgCtx.subject()).map(this::handoff);
    }

    @Transactional
    public WorkbasketHandoffView open(UUID handoffId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record share = requireShare(orgId, handoffId);
        assertRecipientOrCreator(share);
        Workbasket basket = visibleBasket(orgId, share.get("workbasket_id", UUID.class));
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'OPENED', ?, jsonb_build_object('handoffId', ?))
            """, orgId, basket.id(), orgCtx.subject(), handoffId.toString());
        WorkbasketHandoff dto = handoff(share, basket.hiddenItemCount());
        return new WorkbasketHandoffView(dto, basket);
    }

    @Transactional
    public WorkbasketHandoff accept(UUID handoffId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record share = requireShare(orgId, handoffId);
        assertRecipient(share);
        int changed = dsl.execute("""
            UPDATE saved_workbasket_share SET status = 'ACCEPTED', accepted_at = now()
            WHERE organization_id = ? AND id = ? AND status = 'PENDING'
            """, orgId, handoffId);
        if (changed == 0 && !"ACCEPTED".equals(share.get("status", String.class))) {
            throw new ResponseStatusException(BAD_REQUEST, "Üleandmist ei saa enam vastu võtta.");
        }
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'ACCEPTED', ?, jsonb_build_object('handoffId', ?))
            """, orgId, share.get("workbasket_id", UUID.class), orgCtx.subject(), handoffId.toString());
        return handoff(requireShare(orgId, handoffId));
    }

    @Transactional
    public WorkbasketHandoff cancel(UUID handoffId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record share = requireShare(orgId, handoffId);
        if (!orgCtx.subject().equals(share.get("created_by_subject", String.class))) {
            throw new ResponseStatusException(FORBIDDEN, "Üleandmise saab tühistada selle algataja.");
        }
        dsl.execute("""
            UPDATE saved_workbasket_share SET status = 'CANCELLED', cancelled_at = now()
            WHERE organization_id = ? AND id = ? AND status <> 'CANCELLED'
            """, orgId, handoffId);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'SHARE_CANCELLED', ?, jsonb_build_object('handoffId', ?))
            """, orgId, share.get("workbasket_id", UUID.class), orgCtx.subject(), handoffId.toString());
        return handoff(requireShare(orgId, handoffId));
    }

    private Record ownedBasket(UUID orgId, UUID basketId, String subject) {
        Record row = dsl.fetchOne("SELECT id, name FROM saved_workbasket WHERE organization_id = ? AND id = ? AND creator_subject = ?",
            orgId, basketId, subject);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Töökorvi ei leitud.");
        return row;
    }

    private Record requireShare(UUID orgId, UUID id) {
        Record row = dsl.fetchOne("""
            SELECT s.*, b.name AS workbasket_name
            FROM saved_workbasket_share s
            JOIN saved_workbasket b ON b.organization_id = s.organization_id AND b.id = s.workbasket_id
            WHERE s.organization_id = ? AND s.id = ?
            """, orgId, id);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Töökorvi üleandmist ei leitud.");
        return row;
    }

    private void assertRecipientOrCreator(Record share) {
        if (orgCtx.subject().equals(share.get("created_by_subject", String.class))) return;
        assertRecipient(share);
    }

    private void assertRecipient(Record share) {
        String subject = share.get("recipient_subject", String.class);
        String role = share.get("recipient_role", String.class);
        if (subject != null && subject.equals(orgCtx.subject())) return;
        if (role != null && currentRoles().contains(role)) return;
        throw new ResponseStatusException(FORBIDDEN, "See üleandmine ei ole sulle määratud.");
    }

    private Workbasket visibleBasket(UUID orgId, UUID basketId) {
        Record row = dsl.fetchOne("""
            SELECT id, name, purpose, selection_mode, map_filters, created_at, updated_at
            FROM saved_workbasket WHERE organization_id = ? AND id = ?
            """, orgId, basketId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Töökorvi ei leitud.");
        List<WorkbasketParcel> parcels = new ArrayList<>();
        List<WorkbasketOwner> owners = new ArrayList<>();
        int hidden = 0;
        for (Record parcel : dsl.fetch("""
            SELECT p.id, p.cadastral_code, p.name
            FROM saved_workbasket_parcel w JOIN land_parcel p
              ON p.organization_id = w.organization_id AND p.id = w.parcel_id
            WHERE w.organization_id = ? AND w.workbasket_id = ? ORDER BY p.cadastral_code
            """, orgId, basketId)) {
            if (canReadParcel(orgId, parcel.get("id", UUID.class))) {
                parcels.add(new WorkbasketParcel(parcel.get("id", UUID.class), parcel.get("cadastral_code", String.class), parcel.get("name", String.class)));
            } else hidden++;
        }
        for (Record owner : dsl.fetch("""
            SELECT o.id, o.display_name, o.assigned_to_subject
            FROM saved_workbasket_owner w JOIN owner o
              ON o.organization_id = w.organization_id AND o.id = w.owner_id
            WHERE w.organization_id = ? AND w.workbasket_id = ? ORDER BY o.display_name
            """, orgId, basketId)) {
            try {
                ownerAuthorization.assertCanReadAssignedSubject(owner.get("assigned_to_subject", String.class));
                owners.add(new WorkbasketOwner(owner.get("id", UUID.class), owner.get("display_name", String.class)));
            } catch (AccessDeniedException exception) {
                hidden++;
            }
        }
        return new Workbasket(basketId, row.get("name", String.class), row.get("purpose", String.class),
            row.get("selection_mode", String.class), readFilters(row.get("map_filters", JSONB.class)), parcels, owners, hidden,
            instant(row, "created_at"), instant(row, "updated_at"));
    }

    private boolean canReadParcel(UUID orgId, UUID parcelId) {
        try {
            List<String> assignments = dsl.fetch("""
                SELECT o.assigned_to_subject FROM owner_parcel_relation r
                JOIN owner o ON o.organization_id = r.organization_id AND o.id = r.owner_id
                WHERE r.organization_id = ? AND r.parcel_id = ? AND r.active = TRUE
                """, orgId, parcelId).getValues("assigned_to_subject", String.class);
            ownerAuthorization.assertCanReadAnyAssignedSubject(assignments);
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    private Map<String, String> readFilters(JSONB json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json.data(), new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private WorkbasketHandoff handoff(Record row) { return handoff(row, 0); }

    private WorkbasketHandoff handoff(Record row, int hidden) {
        return new WorkbasketHandoff(row.get("id", UUID.class), row.get("workbasket_id", UUID.class),
            row.get("workbasket_name", String.class), row.get("recipient_subject", String.class),
            row.get("recipient_role", String.class), row.get("permission", String.class), row.get("purpose", String.class),
            instant(row, "due_at"), row.get("status", String.class), row.get("created_by_subject", String.class),
            instant(row, "created_at"), instant(row, "accepted_at"), hidden);
    }

    private List<String> currentRoles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return List.of();
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
            .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a).toList();
    }

    private static Instant instant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
