package ee.forestiq.ownership;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.ownership.OwnerParcelRelationService.ConflictView;
import ee.forestiq.ownership.OwnerParcelRelationService.RelationView;
import ee.forestiq.security.OrganizationContext;

/**
 * Read-only keyset path for the million-row ownership relation administration view.
 * Candidate relation keys are selected first; rich owner/parcel/conflict data is
 * joined only for the bounded page.
 */
@Service
public class OwnerParcelRelationCursorService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;

    public OwnerParcelRelationCursorService(DSLContext dsl, OrganizationContext organizationContext) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
    }

    public RelationCursorPage search(String search, Boolean active, String source, int requestedSize, String cursor) {
        UUID orgId = organizationContext.requiredOrganizationId();
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        String term = search == null ? "" : search.trim();
        String sourceFilter = source == null ? "" : source.trim();
        CursorPosition after = decodeCursor(cursor);

        StringBuilder sql = new StringBuilder("""
            SELECT r.owner_id, r.parcel_id, r.updated_at
              FROM owner_parcel_relation r
             WHERE r.organization_id=?
            """);
        List<Object> params = new ArrayList<>();
        params.add(orgId);

        if (active != null) {
            sql.append(" AND r.active=?");
            params.add(active);
        }
        if (!sourceFilter.isBlank()) {
            sql.append(" AND r.source=?");
            params.add(sourceFilter);
        }
        if (!term.isBlank()) {
            sql.append("""
                 AND (
                     EXISTS (
                         SELECT 1 FROM owner o
                          WHERE o.organization_id=r.organization_id AND o.id=r.owner_id
                            AND (o.search_document @@ websearch_to_tsquery('simple', ?)
                                 OR lower(o.personal_or_registry_code) LIKE lower(?) || '%')
                     )
                     OR EXISTS (
                         SELECT 1 FROM land_parcel p
                          WHERE p.organization_id=r.organization_id AND p.id=r.parcel_id
                            AND (lower(p.cadastral_code) LIKE lower(?) || '%'
                                 OR lower(coalesce(p.name, '')) LIKE lower(?) || '%')
                     )
                 )
                """);
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }
        if (after != null) {
            sql.append("""
                 AND (
                     r.updated_at < ?
                     OR (r.updated_at = ? AND r.owner_id > ?)
                     OR (r.updated_at = ? AND r.owner_id = ? AND r.parcel_id > ?)
                 )
                """);
            params.add(after.updatedAt());
            params.add(after.updatedAt());
            params.add(after.ownerId());
            params.add(after.updatedAt());
            params.add(after.ownerId());
            params.add(after.parcelId());
        }
        sql.append(" ORDER BY r.updated_at DESC, r.owner_id ASC, r.parcel_id ASC LIMIT ?");
        params.add(size + 1);

        List<Record> candidates = dsl.fetch(sql.toString(), params.toArray());
        boolean hasNext = candidates.size() > size;
        int pageSize = Math.min(size, candidates.size());
        if (pageSize == 0) return new RelationCursorPage(List.of(), false, null);

        List<RelationKey> keys = new ArrayList<>(pageSize);
        for (int i = 0; i < pageSize; i++) {
            Record row = candidates.get(i);
            keys.add(new RelationKey(
                row.get("owner_id", UUID.class),
                row.get("parcel_id", UUID.class),
                row.get("updated_at", Instant.class)));
        }

        List<RelationView> content = enrich(orgId, keys);
        RelationKey last = keys.get(keys.size() - 1);
        return new RelationCursorPage(content, hasNext,
            hasNext ? encodeCursor(last.updatedAt(), last.ownerId(), last.parcelId()) : null);
    }

    private List<RelationView> enrich(UUID orgId, List<RelationKey> keys) {
        StringBuilder keyPredicate = new StringBuilder();
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        for (RelationKey key : keys) {
            if (!keyPredicate.isEmpty()) keyPredicate.append(" OR ");
            keyPredicate.append("(r.owner_id=? AND r.parcel_id=?)");
            params.add(key.ownerId());
            params.add(key.parcelId());
        }

        String sql = """
            SELECT r.owner_id, o.display_name AS owner_name, o.personal_or_registry_code AS owner_code,
                   r.parcel_id, p.cadastral_code, p.name AS parcel_name, p.area_hectares,
                   r.active, r.source, r.source_external_key, r.source_seen_at, r.deactivated_at,
                   r.created_at, r.updated_at, r.manual_override, r.manual_actor_subject,
                   r.manual_reason, r.manual_changed_at, r.manual_revision,
                   c.id AS conflict_id, c.source AS conflict_source, c.observed_active AS conflict_observed_active,
                   c.observed_at AS conflict_observed_at, c.created_at AS conflict_created_at
              FROM owner_parcel_relation r
              JOIN owner o ON o.organization_id=r.organization_id AND o.id=r.owner_id
              JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id
              LEFT JOIN LATERAL (
                  SELECT x.id, x.source, x.observed_active, x.observed_at, x.created_at
                    FROM ownership_relation_conflict x
                   WHERE x.organization_id=r.organization_id AND x.owner_id=r.owner_id
                     AND x.parcel_id=r.parcel_id AND x.status='OPEN'
                   ORDER BY x.created_at DESC, x.id
                   LIMIT 1
              ) c ON TRUE
             WHERE r.organization_id=? AND (
            """ + keyPredicate + ")";

        Map<RelationIdentity, Integer> order = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            order.put(new RelationIdentity(keys.get(i).ownerId(), keys.get(i).parcelId()), i);
        }

        List<RelationView> result = dsl.fetch(sql, params.toArray()).map(this::mapRelation);
        result.sort((left, right) -> Integer.compare(
            order.getOrDefault(new RelationIdentity(left.ownerId(), left.parcelId()), Integer.MAX_VALUE),
            order.getOrDefault(new RelationIdentity(right.ownerId(), right.parcelId()), Integer.MAX_VALUE)));
        return result;
    }

    private RelationView mapRelation(Record record) {
        UUID conflictId = record.get("conflict_id", UUID.class);
        ConflictView conflict = conflictId == null ? null : new ConflictView(
            conflictId,
            record.get("conflict_source", String.class),
            Boolean.TRUE.equals(record.get("conflict_observed_active", Boolean.class)),
            record.get("conflict_observed_at", Instant.class),
            record.get("conflict_created_at", Instant.class));
        return new RelationView(
            record.get("owner_id", UUID.class), record.get("owner_name", String.class), record.get("owner_code", String.class),
            record.get("parcel_id", UUID.class), record.get("cadastral_code", String.class), record.get("parcel_name", String.class),
            record.get("area_hectares", BigDecimal.class), Boolean.TRUE.equals(record.get("active", Boolean.class)),
            record.get("source", String.class), record.get("source_external_key", String.class),
            record.get("source_seen_at", Instant.class), record.get("deactivated_at", Instant.class),
            record.get("created_at", Instant.class), record.get("updated_at", Instant.class),
            Boolean.TRUE.equals(record.get("manual_override", Boolean.class)),
            record.get("manual_actor_subject", String.class), record.get("manual_reason", String.class),
            record.get("manual_changed_at", Instant.class), record.get("manual_revision", Integer.class), conflict);
    }

    static String encodeCursor(Instant updatedAt, UUID ownerId, UUID parcelId) {
        String raw = updatedAt + "|" + ownerId + "|" + parcelId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException("parts");
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ownership relation cursor");
        }
    }

    public record RelationCursorPage(List<RelationView> content, boolean hasNext, String nextCursor) { }
    record CursorPosition(Instant updatedAt, UUID ownerId, UUID parcelId) { }
    private record RelationKey(UUID ownerId, UUID parcelId, Instant updatedAt) { }
    private record RelationIdentity(UUID ownerId, UUID parcelId) { }
}
