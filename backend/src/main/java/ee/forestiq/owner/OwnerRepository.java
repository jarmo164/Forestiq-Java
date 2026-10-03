package ee.forestiq.owner;

import static ee.forestiq.jooq.tables.Owner.OWNER;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import ee.forestiq.jooq.tables.records.OwnerRecord;

/**
 * jOOQ-backed owner persistence.
 *
 * The application previously pulled in Spring Data JPA and bootstrapped a full
 * Hibernate EntityManagerFactory for this single repository. Keeping owner
 * persistence on the same jOOQ stack as the rest of ForestIQ lets the core
 * runtime skip JPA/Hibernate startup entirely while preserving the lock_version
 * optimistic-locking contract.
 */
@Repository
public class OwnerRepository {
    private final DSLContext dsl;

    public OwnerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Owner> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return dsl.selectFrom(OWNER)
            .where(OWNER.ID.eq(id).and(OWNER.ORGANIZATION_ID.eq(organizationId)))
            .fetchOptional(this::mapOwner);
    }

    /**
     * Acquires all requested owner row locks in one database round-trip and a
     * deterministic database order. This prevents N+1 lookup cost and opposing
     * lock order during concurrent management bulk-assignment commands.
     */
    public List<Owner> findByIdInAndOrganizationIdForUpdate(Collection<UUID> ids, UUID organizationId) {
        if (ids == null || ids.isEmpty()) return List.of();
        return dsl.selectFrom(OWNER)
            .where(OWNER.ID.in(ids).and(OWNER.ORGANIZATION_ID.eq(organizationId)))
            .orderBy(OWNER.ID.asc())
            .forUpdate()
            .fetch(this::mapOwner);
    }

    public Optional<Owner> findByOrganizationIdAndPersonalOrRegistryCode(
            UUID organizationId, String personalOrRegistryCode) {
        return dsl.selectFrom(OWNER)
            .where(OWNER.ORGANIZATION_ID.eq(organizationId)
                .and(OWNER.PERSONAL_OR_REGISTRY_CODE.eq(personalOrRegistryCode)))
            .fetchOptional(this::mapOwner);
    }

    public List<Owner> findByOrganizationIdAndPersonalOrRegistryCodeIn(
            UUID organizationId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return dsl.selectFrom(OWNER)
            .where(OWNER.ORGANIZATION_ID.eq(organizationId)
                .and(OWNER.PERSONAL_OR_REGISTRY_CODE.in(codes)))
            .fetch(this::mapOwner);
    }

    public Owner saveAndFlush(Owner owner) {
        return save(owner);
    }

    public Owner save(Owner owner) {
        // Spring Data JPA treated a null @Version as a new entity even when an
        // import supplied the UUID in advance. Preserve that behavior here.
        if (owner.getVersion() == null) {
            return insert(owner);
        }
        return update(owner);
    }

    public List<Owner> saveAll(Iterable<Owner> owners) {
        List<Owner> saved = new ArrayList<>();
        for (Owner owner : owners) {
            saved.add(save(owner));
        }
        return saved;
    }

    /** jOOQ executes writes immediately, so there is no persistence context to flush. */
    public void flush() {
        // Intentionally empty for compatibility with the former JpaRepository API.
    }

    private Owner insert(Owner owner) {
        UUID id = owner.getId() != null ? owner.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        long version = 0L;

        dsl.insertInto(OWNER)
            .set(OWNER.ID, id)
            .set(OWNER.ORGANIZATION_ID, owner.getOrganizationId())
            .set(OWNER.DISPLAY_NAME, owner.getDisplayName())
            .set(OWNER.PERSONAL_OR_REGISTRY_CODE, owner.getPersonalOrRegistryCode())
            .set(OWNER.EMAIL, owner.getEmail())
            .set(OWNER.PHONE, owner.getPhone())
            .set(OWNER.STATUS, owner.getStatus())
            .set(OWNER.ASSIGNED_TO_SUBJECT, owner.getAssignedToSubject())
            .set(OWNER.OWNER_TYPE, owner.getOwnerType())
            .set(OWNER.ADDRESS, owner.getAddress())
            .set(OWNER.INFO, owner.getInfo())
            .set(OWNER.STATUS_SET_AT, offset(owner.getStatusSetAt()))
            .set(OWNER.LAST_CADASTRE_LIST_REFRESH, owner.getLastCadastreListRefresh())
            .set(OWNER.OUT_OF_ADMIN_SEARCH_FROM, offset(owner.getOutOfAdminSearchFrom()))
            .set(OWNER.OUT_OF_ADMIN_SEARCH_TO, offset(owner.getOutOfAdminSearchTo()))
            .set(OWNER.OUT_OF_ADMIN_SEARCH_REASON, owner.getOutOfAdminSearchReason())
            .set(OWNER.CREATED_AT, offset(now))
            .set(OWNER.UPDATED_AT, offset(now))
            .set(OWNER.LOCK_VERSION, version)
            .execute();

        owner.markInserted(id, now, version);
        return owner;
    }

    private Owner update(Owner owner) {
        Long currentVersion = owner.getVersion();
        if (currentVersion == null) {
            throw new OptimisticLockingFailureException("Owner has no lock_version: " + owner.getId());
        }

        long nextVersion = currentVersion + 1;
        Instant now = Instant.now();
        int updated = dsl.update(OWNER)
            .set(OWNER.DISPLAY_NAME, owner.getDisplayName())
            .set(OWNER.EMAIL, owner.getEmail())
            .set(OWNER.PHONE, owner.getPhone())
            .set(OWNER.STATUS, owner.getStatus())
            .set(OWNER.ASSIGNED_TO_SUBJECT, owner.getAssignedToSubject())
            .set(OWNER.OWNER_TYPE, owner.getOwnerType())
            .set(OWNER.ADDRESS, owner.getAddress())
            .set(OWNER.INFO, owner.getInfo())
            .set(OWNER.STATUS_SET_AT, offset(owner.getStatusSetAt()))
            .set(OWNER.LAST_CADASTRE_LIST_REFRESH, owner.getLastCadastreListRefresh())
            .set(OWNER.OUT_OF_ADMIN_SEARCH_FROM, offset(owner.getOutOfAdminSearchFrom()))
            .set(OWNER.OUT_OF_ADMIN_SEARCH_TO, offset(owner.getOutOfAdminSearchTo()))
            .set(OWNER.OUT_OF_ADMIN_SEARCH_REASON, owner.getOutOfAdminSearchReason())
            .set(OWNER.UPDATED_AT, offset(now))
            .set(OWNER.LOCK_VERSION, nextVersion)
            .where(OWNER.ID.eq(owner.getId())
                .and(OWNER.ORGANIZATION_ID.eq(owner.getOrganizationId()))
                .and(OWNER.LOCK_VERSION.eq(currentVersion)))
            .execute();

        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                "Owner was modified concurrently or no longer exists: " + owner.getId());
        }

        owner.markUpdated(now, nextVersion);
        return owner;
    }

    private Owner mapOwner(OwnerRecord record) {
        Owner owner = new Owner(
            record.getId(),
            record.getOrganizationId(),
            record.getDisplayName(),
            record.getPersonalOrRegistryCode(),
            record.getEmail(),
            record.getPhone(),
            record.getStatus(),
            record.getAssignedToSubject());
        owner.setOwnerType(record.getOwnerType());
        owner.setAddress(record.getAddress());
        owner.setInfo(record.getInfo());
        owner.restorePersistenceState(
            instant(record.getStatusSetAt()),
            record.getLastCadastreListRefresh(),
            instant(record.getOutOfAdminSearchFrom()),
            instant(record.getOutOfAdminSearchTo()),
            record.getOutOfAdminSearchReason(),
            instant(record.getCreatedAt()),
            instant(record.getUpdatedAt()),
            record.getLockVersion());
        return owner;
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
