package ee.forestiq.ownership;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/**
 * Application service for the canonical owner_parcel_relation model.
 *
 * Manual changes are protected from automated synchronization. Provider
 * disagreement is represented by ownership_relation_conflict instead of
 * silently changing a manually confirmed relationship.
 */
@Service
public class OwnerParcelRelationService {

    private static final String MANUAL_SOURCE = "MANUAL";

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final CacheManager cacheManager;

    public OwnerParcelRelationService(DSLContext dsl, OrganizationContext organizationContext, CacheManager cacheManager) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.cacheManager = cacheManager;
    }

    public List<RelationView> search(String search, Boolean active, String source, int requestedLimit) {
        UUID orgId = organizationContext.requiredOrganizationId();
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        String term = search == null ? "" : search.trim();
        String sourceFilter = source == null ? "" : source.trim();

        StringBuilder sql = new StringBuilder("""
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
             WHERE r.organization_id=?
            """);
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        if (!term.isBlank()) {
            sql.append(" AND (o.display_name ILIKE ? OR o.personal_or_registry_code ILIKE ? OR p.cadastral_code ILIKE ? OR p.name ILIKE ?)");
            String like = "%" + term + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (active != null) {
            sql.append(" AND r.active=?");
            params.add(active);
        }
        if (!sourceFilter.isBlank()) {
            sql.append(" AND r.source=?");
            params.add(sourceFilter);
        }
        sql.append(" ORDER BY r.active DESC, o.display_name ASC, p.cadastral_code ASC LIMIT ?");
        params.add(limit);
        return dsl.fetch(sql.toString(), params.toArray()).map(this::mapRelation);
    }

    public RelationView createManualRelation(UUID ownerId, String cadastralCode, String reason) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String actor = organizationContext.subject();
        String safeReason = requireReason(reason);
        String code = requireCadastre(cadastralCode);

        UUID parcelId = dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            requireOwner(tx, orgId, ownerId);
            UUID targetParcelId = requireParcelByCadastre(tx, orgId, code);
            Record existing = tx.fetchOne("""
                SELECT active, source, manual_override, manual_revision
                  FROM owner_parcel_relation
                 WHERE organization_id=? AND owner_id=? AND parcel_id=?
                 FOR UPDATE
                """, orgId, ownerId, targetParcelId);
            if (existing != null && Boolean.TRUE.equals(existing.get("active", Boolean.class))) {
                return targetParcelId;
            }

            Instant now = Instant.now();
            if (existing == null) {
                tx.execute("""
                    INSERT INTO owner_parcel_relation
                        (organization_id, owner_id, parcel_id, source, active, source_seen_at,
                         deactivated_at, manual_override, manual_actor_subject, manual_reason,
                         manual_changed_at, manual_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, TRUE, ?::timestamptz, NULL, TRUE, ?, ?, ?::timestamptz, 1, ?::timestamptz, ?::timestamptz)
                    """, orgId, ownerId, targetParcelId, MANUAL_SOURCE, now, actor, safeReason, now, now, now);
                recordAudit(tx, orgId, ownerId, targetParcelId, "CREATE_MANUAL", MANUAL_SOURCE,
                    actor, safeReason, "{\"active\":true}");
            } else {
                tx.execute("""
                    UPDATE owner_parcel_relation
                       SET active=TRUE, source=?, source_seen_at=?::timestamptz, deactivated_at=NULL,
                           manual_override=TRUE, manual_actor_subject=?, manual_reason=?,
                           manual_changed_at=?::timestamptz, manual_revision=manual_revision+1, updated_at=?::timestamptz
                     WHERE organization_id=? AND owner_id=? AND parcel_id=?
                    """, MANUAL_SOURCE, now, actor, safeReason, now, now, orgId, ownerId, targetParcelId);
                supersedeOpenConflicts(tx, orgId, ownerId, targetParcelId, actor, safeReason);
                recordAudit(tx, orgId, ownerId, targetParcelId, "REACTIVATE_MANUAL", MANUAL_SOURCE,
                    actor, safeReason, "{\"active\":true}");
            }
            return targetParcelId;
        });
        evictOwnerParcels(orgId, ownerId);
        return requireRelation(orgId, ownerId, parcelId);
    }

    public RelationView endManualRelation(UUID ownerId, UUID parcelId, String reason) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String actor = organizationContext.subject();
        String safeReason = requireReason(reason);

        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            requireOwner(tx, orgId, ownerId);
            requireParcel(tx, orgId, parcelId);
            Record existing = tx.fetchOne("""
                SELECT active, source
                  FROM owner_parcel_relation
                 WHERE organization_id=? AND owner_id=? AND parcel_id=?
                 FOR UPDATE
                """, orgId, ownerId, parcelId);
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Omaniku ja katastriüksuse seost ei leitud.");
            }
            if (!Boolean.TRUE.equals(existing.get("active", Boolean.class))) {
                return;
            }
            String relationSource = existing.get("source", String.class);
            Instant now = Instant.now();
            tx.execute("""
                UPDATE owner_parcel_relation
                   SET active=FALSE, deactivated_at=?::timestamptz, manual_override=TRUE,
                       manual_actor_subject=?, manual_reason=?, manual_changed_at=?::timestamptz,
                       manual_revision=manual_revision+1, updated_at=?::timestamptz
                 WHERE organization_id=? AND owner_id=? AND parcel_id=?
                """, now, actor, safeReason, now, now, orgId, ownerId, parcelId);
            supersedeOpenConflicts(tx, orgId, ownerId, parcelId, actor, safeReason);
            recordAudit(tx, orgId, ownerId, parcelId, "END_MANUAL", relationSource,
                actor, safeReason, "{\"active\":false}");
        });
        evictOwnerParcels(orgId, ownerId);
        return requireRelation(orgId, ownerId, parcelId);
    }

    public RelationView resolveConflict(UUID conflictId, Resolution resolution, String reason) {
        UUID orgId = organizationContext.requiredOrganizationId();
        String actor = organizationContext.subject();
        String safeReason = requireReason(reason);
        if (resolution == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Konflikti lahendus on kohustuslik.");
        }

        RelationKey key = dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record conflict = tx.fetchOne("""
                SELECT id, owner_id, parcel_id, source, observed_active, observed_at, status
                  FROM ownership_relation_conflict
                 WHERE organization_id=? AND id=?
                 FOR UPDATE
                """, orgId, conflictId);
            if (conflict == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Omandisuhte konflikti ei leitud.");
            }
            UUID ownerId = conflict.get("owner_id", UUID.class);
            UUID parcelId = conflict.get("parcel_id", UUID.class);
            if (!"OPEN".equals(conflict.get("status", String.class))) {
                return new RelationKey(ownerId, parcelId);
            }
            String providerSource = conflict.get("source", String.class);
            boolean observedActive = Boolean.TRUE.equals(conflict.get("observed_active", Boolean.class));
            Instant observedAt = conflict.get("observed_at", Instant.class);
            Record relation = tx.fetchOne("""
                SELECT source FROM owner_parcel_relation
                 WHERE organization_id=? AND owner_id=? AND parcel_id=?
                 FOR UPDATE
                """, orgId, ownerId, parcelId);
            if (relation == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Konflikti aluseks olnud omandisuhe puudub.");
            }

            if (resolution == Resolution.ACCEPT_PROVIDER) {
                tx.execute("""
                    UPDATE owner_parcel_relation
                       SET active=?, source=?, source_seen_at=?::timestamptz, deactivated_at=?::timestamptz,
                           manual_override=FALSE, manual_actor_subject=NULL, manual_reason=NULL,
                           manual_changed_at=NULL, manual_revision=manual_revision+1, updated_at=CURRENT_TIMESTAMP
                     WHERE organization_id=? AND owner_id=? AND parcel_id=?
                    """, observedActive, providerSource, observedAt,
                    observedActive ? null : Instant.now(), orgId, ownerId, parcelId);
                recordAudit(tx, orgId, ownerId, parcelId, "CONFLICT_ACCEPT_PROVIDER", providerSource,
                    actor, safeReason, "{\"observedActive\":" + observedActive + "}");
            } else {
                String relationSource = relation.get("source", String.class);
                recordAudit(tx, orgId, ownerId, parcelId, "CONFLICT_KEEP_MANUAL", relationSource,
                    actor, safeReason, "{\"observedActive\":" + observedActive + "}");
            }

            tx.execute("""
                UPDATE ownership_relation_conflict
                   SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP, resolved_by_subject=?,
                       resolution=?, resolution_reason=?
                 WHERE organization_id=? AND id=? AND status='OPEN'
                """, actor, resolution.name(), safeReason, orgId, conflictId);
            return new RelationKey(ownerId, parcelId);
        });
        evictOwnerParcels(orgId, key.ownerId());
        return requireRelation(orgId, key.ownerId(), key.parcelId());
    }

    public List<AuditView> audit(UUID ownerId, UUID parcelId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        requireOwner(dsl, orgId, ownerId);
        requireParcel(dsl, orgId, parcelId);
        return dsl.fetch("""
            SELECT id, action, relation_source, actor_subject, reason, payload::text AS payload, created_at
              FROM owner_parcel_relation_audit
             WHERE organization_id=? AND owner_id=? AND parcel_id=?
             ORDER BY created_at DESC, id DESC
             LIMIT 200
            """, orgId, ownerId, parcelId).map(record -> new AuditView(
                record.get("id", UUID.class), record.get("action", String.class),
                record.get("relation_source", String.class), record.get("actor_subject", String.class),
                record.get("reason", String.class), record.get("payload", String.class),
                record.get("created_at", Instant.class)));
    }

    private RelationView requireRelation(UUID orgId, UUID ownerId, UUID parcelId) {
        Record record = dsl.fetchOne("""
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
             WHERE r.organization_id=? AND r.owner_id=? AND r.parcel_id=?
            """, orgId, ownerId, parcelId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Omaniku ja katastriüksuse seost ei leitud.");
        }
        return mapRelation(record);
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

    private void requireOwner(DSLContext tx, UUID orgId, UUID ownerId) {
        if (tx.fetchOne("SELECT 1 FROM owner WHERE organization_id=? AND id=?", orgId, ownerId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Omanikku ei leitud.");
        }
    }

    private UUID requireParcelByCadastre(DSLContext tx, UUID orgId, String cadastralCode) {
        Record parcel = tx.fetchOne("SELECT id FROM land_parcel WHERE organization_id=? AND cadastral_code=?", orgId, cadastralCode);
        if (parcel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Katastriüksust ei leitud. Sünkroniseeri esmalt katastriandmed.");
        }
        return parcel.get("id", UUID.class);
    }

    private void requireParcel(DSLContext tx, UUID orgId, UUID parcelId) {
        if (tx.fetchOne("SELECT 1 FROM land_parcel WHERE organization_id=? AND id=?", orgId, parcelId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Katastriüksust ei leitud.");
        }
    }

    private void recordAudit(DSLContext tx, UUID orgId, UUID ownerId, UUID parcelId, String action,
                             String source, String actor, String reason, String payload) {
        tx.execute("""
            INSERT INTO owner_parcel_relation_audit
                (id, organization_id, owner_id, parcel_id, action, relation_source,
                 actor_subject, reason, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgId, ownerId, parcelId, action,
            source == null ? MANUAL_SOURCE : source, actor, reason, payload);
    }

    private void supersedeOpenConflicts(DSLContext tx, UUID orgId, UUID ownerId, UUID parcelId,
                                        String actor, String reason) {
        tx.execute("""
            UPDATE ownership_relation_conflict
               SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP, resolved_by_subject=?,
                   resolution='KEEP_MANUAL', resolution_reason=?
             WHERE organization_id=? AND owner_id=? AND parcel_id=? AND status='OPEN'
            """, actor, "Uus käsitsi otsus: " + reason, orgId, ownerId, parcelId);
    }

    private void evictOwnerParcels(UUID orgId, UUID ownerId) {
        Cache cache = cacheManager.getCache("owner-parcels");
        if (cache != null) cache.evict(orgId + ":" + ownerId);
    }

    private String requireReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Muudatuse põhjus on kohustuslik.");
        }
        if (reason.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Muudatuse põhjus on liiga pikk.");
        }
        return reason;
    }

    private String requireCadastre(String value) {
        String code = value == null ? "" : value.trim();
        if (code.isBlank() || code.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Katastritunnus on kohustuslik.");
        }
        return code;
    }

    public enum Resolution { KEEP_MANUAL, ACCEPT_PROVIDER }

    public record RelationView(
        UUID ownerId, String ownerName, String ownerCode,
        UUID parcelId, String cadastralCode, String parcelName, BigDecimal areaHectares,
        boolean active, String source, String sourceExternalKey, Instant sourceSeenAt, Instant deactivatedAt,
        Instant createdAt, Instant updatedAt, boolean manualOverride, String manualActorSubject,
        String manualReason, Instant manualChangedAt, Integer manualRevision, ConflictView conflict) { }

    public record ConflictView(UUID id, String source, boolean observedActive, Instant observedAt, Instant createdAt) { }

    public record AuditView(UUID id, String action, String relationSource, String actorSubject,
                            String reason, String payload, Instant createdAt) { }

    private record RelationKey(UUID ownerId, UUID parcelId) { }
}
