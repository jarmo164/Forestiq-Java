package ee.forestiq.decision;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ee.forestiq.decision.DecisionDtos.SaveWorkbasketRequest;
import ee.forestiq.decision.DecisionDtos.Workbasket;
import ee.forestiq.decision.DecisionDtos.WorkbasketOwner;
import ee.forestiq.decision.DecisionDtos.WorkbasketParcel;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class SavedWorkbasketService {

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final ObjectMapper objectMapper;

    public SavedWorkbasketService(DSLContext dsl, OrganizationContext orgCtx,
                                  OwnerAuthorizationService ownerAuthorization, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Workbasket> list() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        return dsl.fetch("""
            SELECT id, name, purpose, selection_mode, map_filters, created_at, updated_at
            FROM saved_workbasket
            WHERE organization_id = ? AND creator_subject = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT 200
            """, orgId, subject).map(row -> toWorkbasket(orgId, row, false));
    }

    @Transactional
    public Workbasket get(UUID id) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record row = requireOwnedBasket(orgId, id);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'OPENED', ?, '{}'::jsonb)
            """, orgId, id, orgCtx.subject());
        return toWorkbasket(orgId, row, true);
    }

    @Transactional
    public Workbasket create(SaveWorkbasketRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        UUID id = UUID.randomUUID();
        Selection selection = validateSelection(orgId, request);
        String filters = jsonFilters(request.filters());
        dsl.execute("""
            INSERT INTO saved_workbasket
                (id, organization_id, creator_subject, name, purpose, selection_mode, map_filters)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """, id, orgId, orgCtx.subject(), request.name().trim(), trimToNull(request.purpose()),
            request.dynamic() ? "DYNAMIC" : "FIXED", filters);
        replaceMembers(orgId, id, selection);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'CREATED', ?, jsonb_build_object('parcelCount', ?, 'ownerCount', ?))
            """, orgId, id, orgCtx.subject(), selection.parcels().size(), selection.owners().size());
        return getWithoutAudit(orgId, id);
    }

    @Transactional
    public Workbasket update(UUID id, SaveWorkbasketRequest request) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireOwnedBasket(orgId, id);
        Selection selection = validateSelection(orgId, request);
        dsl.execute("""
            UPDATE saved_workbasket
               SET name = ?, purpose = ?, selection_mode = ?, map_filters = ?::jsonb, updated_at = now()
             WHERE organization_id = ? AND id = ? AND creator_subject = ?
            """, request.name().trim(), trimToNull(request.purpose()), request.dynamic() ? "DYNAMIC" : "FIXED",
            jsonFilters(request.filters()), orgId, id, orgCtx.subject());
        replaceMembers(orgId, id, selection);
        dsl.execute("""
            INSERT INTO saved_workbasket_event (organization_id, workbasket_id, event_type, actor_subject, payload)
            VALUES (?, ?, 'UPDATED', ?, jsonb_build_object('parcelCount', ?, 'ownerCount', ?))
            """, orgId, id, orgCtx.subject(), selection.parcels().size(), selection.owners().size());
        return getWithoutAudit(orgId, id);
    }

    private Selection validateSelection(UUID orgId, SaveWorkbasketRequest request) {
        Set<String> codes = new LinkedHashSet<>(request.cadastralCodes() == null ? List.of() : request.cadastralCodes());
        Set<UUID> ownerIds = new LinkedHashSet<>(request.ownerIds() == null ? List.of() : request.ownerIds());
        if (codes.size() > 1000 || ownerIds.size() > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "Töökorvi valik on liiga suur.");
        }
        List<WorkbasketParcel> parcels = new ArrayList<>();
        for (String rawCode : codes) {
            String code = rawCode == null ? "" : rawCode.trim();
            Record row = dsl.fetchOne("""
                SELECT id, cadastral_code, name FROM land_parcel
                WHERE organization_id = ? AND cadastral_code = ?
                """, orgId, code);
            if (row == null) throw new ResponseStatusException(BAD_REQUEST, "Katastriüksust ei leitud: " + code);
            UUID parcelId = row.get("id", UUID.class);
            assertCanReadParcel(orgId, parcelId);
            parcels.add(new WorkbasketParcel(parcelId, row.get("cadastral_code", String.class), row.get("name", String.class)));
        }
        List<WorkbasketOwner> owners = new ArrayList<>();
        for (UUID ownerId : ownerIds) {
            Record row = dsl.fetchOne("SELECT display_name, assigned_to_subject FROM owner WHERE organization_id = ? AND id = ?", orgId, ownerId);
            if (row == null) throw new ResponseStatusException(BAD_REQUEST, "Omanikku ei leitud: " + ownerId);
            ownerAuthorization.assertCanReadAssignedSubject(row.get("assigned_to_subject", String.class));
            owners.add(new WorkbasketOwner(ownerId, row.get("display_name", String.class)));
        }
        return new Selection(parcels, owners);
    }

    private void replaceMembers(UUID orgId, UUID basketId, Selection selection) {
        dsl.execute("DELETE FROM saved_workbasket_parcel WHERE organization_id = ? AND workbasket_id = ?", orgId, basketId);
        dsl.execute("DELETE FROM saved_workbasket_owner WHERE organization_id = ? AND workbasket_id = ?", orgId, basketId);
        for (WorkbasketParcel parcel : selection.parcels()) {
            dsl.execute("""
                INSERT INTO saved_workbasket_parcel (organization_id, workbasket_id, parcel_id)
                VALUES (?, ?, ?)
                """, orgId, basketId, parcel.id());
        }
        for (WorkbasketOwner owner : selection.owners()) {
            dsl.execute("""
                INSERT INTO saved_workbasket_owner (organization_id, workbasket_id, owner_id)
                VALUES (?, ?, ?)
                """, orgId, basketId, owner.id());
        }
    }

    private Workbasket getWithoutAudit(UUID orgId, UUID id) {
        return toWorkbasket(orgId, requireOwnedBasket(orgId, id), true);
    }

    private Record requireOwnedBasket(UUID orgId, UUID id) {
        Record row = dsl.fetchOne("""
            SELECT id, name, purpose, selection_mode, map_filters, created_at, updated_at
            FROM saved_workbasket
            WHERE organization_id = ? AND id = ? AND creator_subject = ?
            """, orgId, id, orgCtx.subject());
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Töökorvi ei leitud.");
        return row;
    }

    private Workbasket toWorkbasket(UUID orgId, Record row, boolean includeItems) {
        UUID id = row.get("id", UUID.class);
        List<WorkbasketParcel> visibleParcels = new ArrayList<>();
        List<WorkbasketOwner> visibleOwners = new ArrayList<>();
        int hidden = 0;
        if (includeItems) {
            var parcelRows = dsl.fetch("""
                SELECT p.id, p.cadastral_code, p.name
                FROM saved_workbasket_parcel w
                JOIN land_parcel p ON p.organization_id = w.organization_id AND p.id = w.parcel_id
                WHERE w.organization_id = ? AND w.workbasket_id = ?
                ORDER BY p.cadastral_code
                """, orgId, id);
            for (Record parcel : parcelRows) {
                UUID parcelId = parcel.get("id", UUID.class);
                if (canReadParcel(orgId, parcelId)) {
                    visibleParcels.add(new WorkbasketParcel(parcelId, parcel.get("cadastral_code", String.class), parcel.get("name", String.class)));
                } else hidden++;
            }
            var ownerRows = dsl.fetch("""
                SELECT o.id, o.display_name, o.assigned_to_subject
                FROM saved_workbasket_owner w
                JOIN owner o ON o.organization_id = w.organization_id AND o.id = w.owner_id
                WHERE w.organization_id = ? AND w.workbasket_id = ?
                ORDER BY o.display_name, o.id
                """, orgId, id);
            for (Record owner : ownerRows) {
                try {
                    ownerAuthorization.assertCanReadAssignedSubject(owner.get("assigned_to_subject", String.class));
                    visibleOwners.add(new WorkbasketOwner(owner.get("id", UUID.class), owner.get("display_name", String.class)));
                } catch (AccessDeniedException exception) {
                    hidden++;
                }
            }
        }
        return new Workbasket(id, row.get("name", String.class), row.get("purpose", String.class),
            row.get("selection_mode", String.class), readFilters(row.get("map_filters", JSONB.class)),
            visibleParcels, visibleOwners, hidden, toInstant(row, "created_at"), toInstant(row, "updated_at"));
    }

    private void assertCanReadParcel(UUID orgId, UUID parcelId) {
        List<String> assignments = dsl.fetch("""
            SELECT o.assigned_to_subject
            FROM owner_parcel_relation r
            JOIN owner o ON o.organization_id = r.organization_id AND o.id = r.owner_id
            WHERE r.organization_id = ? AND r.parcel_id = ? AND r.active = TRUE
            """, orgId, parcelId).getValues("assigned_to_subject", String.class);
        ownerAuthorization.assertCanReadAnyAssignedSubject(assignments);
    }

    private boolean canReadParcel(UUID orgId, UUID parcelId) {
        try {
            assertCanReadParcel(orgId, parcelId);
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    private String jsonFilters(Map<String, String> filters) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (filters != null) {
            filters.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && key.length() <= 80 && value.length() <= 500) {
                    safe.put(key.trim(), value);
                }
            });
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Töökorvi filtrite serialiseerimine ebaõnnestus.", exception);
        }
    }

    private Map<String, String> readFilters(JSONB json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json.data(), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Töökorvi filtrite lugemine ebaõnnestus.", exception);
        }
    }

    private static java.time.Instant toInstant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Selection(List<WorkbasketParcel> parcels, List<WorkbasketOwner> owners) {}
}
