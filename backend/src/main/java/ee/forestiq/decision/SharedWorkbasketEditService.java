package ee.forestiq.decision;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PRECONDITION_FAILED;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.decision.DecisionDtos.SaveWorkbasketRequest;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class SharedWorkbasketEditService {
    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final ObjectMapper objectMapper;

    public SharedWorkbasketEditService(DSLContext dsl, OrganizationContext orgCtx,
                                       OwnerAuthorizationService ownerAuthorization, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void update(UUID handoffId, SaveWorkbasketRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record share = dsl.fetchOne("""
            SELECT id, workbasket_id, recipient_subject, recipient_role, permission, status
              FROM saved_workbasket_share
             WHERE organization_id = ? AND id = ?
             FOR UPDATE
            """, orgId, handoffId);
        if (share == null) throw new ResponseStatusException(NOT_FOUND, "Töökorvi üleandmist ei leitud.");
        assertRecipient(share);
        if (!"ACCEPTED".equals(share.get("status", String.class))) {
            throw new ResponseStatusException(PRECONDITION_FAILED, "Muutmisõigusega töö tuleb enne vastu võtta.");
        }
        if (!"EDIT".equals(share.get("permission", String.class))) {
            throw new ResponseStatusException(FORBIDDEN, "See üleandmine annab ainult lugemisõiguse.");
        }
        validate(request);
        UUID basketId = share.get("workbasket_id", UUID.class);
        dsl.execute("""
            UPDATE saved_workbasket
               SET name = ?, purpose = ?, selection_mode = ?, map_filters = CAST(? AS jsonb), updated_at = now()
             WHERE organization_id = ? AND id = ?
            """, request.name().trim(), trim(request.purpose()), request.dynamic() ? "DYNAMIC" : "FIXED",
            jsonFilters(request.filters()), orgId, basketId);
        replaceVisibleMembers(orgId, basketId, request);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'UPDATED', ?, jsonb_build_object('handoffId', ?, 'sharedEdit', true))
            """, orgId, basketId, orgCtx.subject(), handoffId.toString());
    }

    private void validate(SaveWorkbasketRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Töökorvi nimi on kohustuslik.");
        }
        Set<String> codes = new LinkedHashSet<>(request.cadastralCodes() == null ? List.of() : request.cadastralCodes());
        Set<UUID> owners = new LinkedHashSet<>(request.ownerIds() == null ? List.of() : request.ownerIds());
        if (codes.size() > 1000 || owners.size() > 500) throw new ResponseStatusException(BAD_REQUEST, "Töökorvi valik on liiga suur.");
        UUID orgId = orgCtx.requiredOrganizationId();
        for (String raw : codes) {
            String code = raw == null ? "" : raw.trim();
            Record parcel = dsl.fetchOne("SELECT id FROM land_parcel WHERE organization_id = ? AND cadastral_code = ?", orgId, code);
            if (parcel == null) throw new ResponseStatusException(BAD_REQUEST, "Katastriüksust ei leitud: " + code);
            assertParcelAccess(orgId, parcel.get("id", UUID.class));
        }
        for (UUID ownerId : owners) {
            Record owner = dsl.fetchOne("SELECT assigned_to_subject FROM owner WHERE organization_id = ? AND id = ?", orgId, ownerId);
            if (owner == null) throw new ResponseStatusException(BAD_REQUEST, "Omanikku ei leitud: " + ownerId);
            ownerAuthorization.assertCanReadAssignedSubject(owner.get("assigned_to_subject", String.class));
        }
    }

    /** Replace only members visible to the shared editor; hidden members are preserved. */
    private void replaceVisibleMembers(UUID orgId, UUID basketId, SaveWorkbasketRequest request) {
        for (UUID parcelId : dsl.fetch("""
            SELECT parcel_id FROM saved_workbasket_parcel WHERE organization_id = ? AND workbasket_id = ?
            """, orgId, basketId).getValues("parcel_id", UUID.class)) {
            if (canReadParcel(orgId, parcelId)) {
                dsl.execute("DELETE FROM saved_workbasket_parcel WHERE organization_id = ? AND workbasket_id = ? AND parcel_id = ?",
                    orgId, basketId, parcelId);
            }
        }
        for (UUID ownerId : dsl.fetch("""
            SELECT owner_id FROM saved_workbasket_owner WHERE organization_id = ? AND workbasket_id = ?
            """, orgId, basketId).getValues("owner_id", UUID.class)) {
            if (canReadOwner(orgId, ownerId)) {
                dsl.execute("DELETE FROM saved_workbasket_owner WHERE organization_id = ? AND workbasket_id = ? AND owner_id = ?",
                    orgId, basketId, ownerId);
            }
        }
        for (String raw : new LinkedHashSet<>(request.cadastralCodes() == null ? List.of() : request.cadastralCodes())) {
            String code = raw == null ? "" : raw.trim();
            UUID parcelId = dsl.fetchOne("SELECT id FROM land_parcel WHERE organization_id = ? AND cadastral_code = ?", orgId, code)
                .get("id", UUID.class);
            dsl.execute("""
                INSERT INTO saved_workbasket_parcel (organization_id, workbasket_id, parcel_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, orgId, basketId, parcelId);
        }
        for (UUID ownerId : new LinkedHashSet<>(request.ownerIds() == null ? List.of() : request.ownerIds())) {
            dsl.execute("""
                INSERT INTO saved_workbasket_owner (organization_id, workbasket_id, owner_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, orgId, basketId, ownerId);
        }
    }

    private boolean canReadParcel(UUID orgId, UUID parcelId) {
        try {
            assertParcelAccess(orgId, parcelId);
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    private boolean canReadOwner(UUID orgId, UUID ownerId) {
        Record owner = dsl.fetchOne("SELECT assigned_to_subject FROM owner WHERE organization_id = ? AND id = ?", orgId, ownerId);
        if (owner == null) return false;
        try {
            ownerAuthorization.assertCanReadAssignedSubject(owner.get("assigned_to_subject", String.class));
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    private void assertParcelAccess(UUID orgId, UUID parcelId) {
        List<String> assignments = dsl.fetch("""
            SELECT o.assigned_to_subject
              FROM owner_parcel_relation r
              JOIN owner o ON o.organization_id = r.organization_id AND o.id = r.owner_id
             WHERE r.organization_id = ? AND r.parcel_id = ? AND r.active = TRUE
            """, orgId, parcelId).getValues("assigned_to_subject", String.class);
        ownerAuthorization.assertCanReadAnyAssignedSubject(assignments);
    }

    private void assertRecipient(Record share) {
        String direct = share.get("recipient_subject", String.class);
        String role = share.get("recipient_role", String.class);
        if (direct != null && direct.equals(orgCtx.subject())) return;
        if (role != null && currentRoles().contains(role)) return;
        throw new ResponseStatusException(FORBIDDEN, "See üleandmine ei ole sulle määratud.");
    }

    private List<String> currentRoles() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return List.of();
        return authentication.getAuthorities().stream().map(a -> a.getAuthority())
            .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a).toList();
    }

    private String jsonFilters(Map<String, String> filters) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (filters != null) filters.forEach((key, value) -> {
            if (key != null && !key.isBlank() && key.length() <= 80 && value != null && value.length() <= 500) safe.put(key.trim(), value);
        });
        try { return objectMapper.writeValueAsString(safe); }
        catch (Exception exception) { throw new IllegalStateException("Töökorvi filtrite serialiseerimine ebaõnnestus.", exception); }
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
