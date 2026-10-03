package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ee.forestiq.security.OrganizationContext;

@Testcontainers(disabledWithoutDocker = true)
class OwnerParcelRelationServiceIntegrationTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ORG = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PARCEL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_PARCEL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String CADASTRAL_CODE = "12345:001:0001";
    private static final String OTHER_CADASTRAL_CODE = "99999:001:0001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("forestiq_test")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;
    private ConcurrentMapCacheManager cacheManager;
    private OwnerParcelRelationService service;

    @BeforeEach
    void setUp() {
        dsl = DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dsl.configuration().set(SQLDialect.POSTGRES);
        resetSchema();
        cacheManager = new ConcurrentMapCacheManager("owner-parcels");

        OrganizationContext organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG);
        when(organizationContext.subject()).thenReturn("e2e-admin-subject");
        service = new OwnerParcelRelationService(dsl, organizationContext, cacheManager);

        insertOrganization(ORG, "ForestIQ");
        insertOrganization(OTHER_ORG, "Other tenant");
        insertOwner(ORG, OWNER_A, "Owner A", "48901010011");
        insertOwner(ORG, OWNER_B, "Owner B", "38901010022");
        insertOwner(OTHER_ORG, OTHER_OWNER, "Other Owner", "28901010033");
        insertParcel(ORG, PARCEL, CADASTRAL_CODE, "Shared parcel");
        insertParcel(OTHER_ORG, OTHER_PARCEL, OTHER_CADASTRAL_CODE, "Other parcel");
    }

    @Test
    void lifecycleIsIdempotentAuditedCacheSafeAndPreservesCoOwner() {
        String cacheKeyA = ORG + ":" + OWNER_A;
        cacheManager.getCache("owner-parcels").put(cacheKeyA, "stale");

        var createdA = service.createManualRelation(OWNER_A, CADASTRAL_CODE, "Verified from signed owner document");
        assertThat(createdA.active()).isTrue();
        assertThat(createdA.manualOverride()).isTrue();
        assertThat(createdA.source()).isEqualTo("MANUAL");
        assertThat(cacheManager.getCache("owner-parcels").get(cacheKeyA)).isNull();
        assertThat(auditCount(OWNER_A, PARCEL)).isEqualTo(1);

        service.createManualRelation(OWNER_A, CADASTRAL_CODE, "Duplicate retry must be idempotent");
        assertThat(auditCount(OWNER_A, PARCEL)).isEqualTo(1);

        service.createManualRelation(OWNER_B, CADASTRAL_CODE, "Second verified co-owner");
        assertThat(activeRelationCount(PARCEL)).isEqualTo(2);

        service.endManualRelation(OWNER_A, PARCEL, "Owner A transferred their share");
        assertThat(relationActive(OWNER_A, PARCEL)).isFalse();
        assertThat(relationActive(OWNER_B, PARCEL)).isTrue();
        assertThat(auditCount(OWNER_A, PARCEL)).isEqualTo(2);

        service.createManualRelation(OWNER_A, CADASTRAL_CODE, "Transfer was cancelled; restore relation");
        assertThat(relationActive(OWNER_A, PARCEL)).isTrue();
        assertThat(relationActive(OWNER_B, PARCEL)).isTrue();
        assertThat(auditCount(OWNER_A, PARCEL)).isEqualTo(3);

        var audit = service.audit(OWNER_A, PARCEL);
        assertThat(audit).hasSize(3);
        assertThat(audit).allSatisfy(event -> {
            assertThat(event.actorSubject()).isEqualTo("e2e-admin-subject");
            assertThat(event.reason()).isNotBlank();
            assertThat(event.createdAt()).isNotNull();
        });
    }

    @Test
    void organizationScopePreventsCrossTenantLinksAndSearchLeaks() {
        service.createManualRelation(OWNER_A, CADASTRAL_CODE, "Local relation");
        insertRelation(OTHER_ORG, OTHER_OWNER, OTHER_PARCEL, true, "MANUAL", false, 0);

        assertThatThrownBy(() -> service.createManualRelation(OTHER_OWNER, CADASTRAL_CODE, "Cross-tenant owner"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
        assertThatThrownBy(() -> service.createManualRelation(OWNER_A, OTHER_CADASTRAL_CODE, "Cross-tenant parcel"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");

        var visible = service.search("", null, "", 100);
        assertThat(visible).extracting(OwnerParcelRelationService.RelationView::ownerId)
            .containsExactly(OWNER_A);
        assertThat(visible).noneMatch(row -> row.ownerId().equals(OTHER_OWNER));
    }

    @Test
    void acceptingProviderConflictIsDeterministicAndAudited() {
        service.createManualRelation(OWNER_A, CADASTRAL_CODE, "Manual active decision");
        UUID conflictId = UUID.randomUUID();
        dsl.execute("""
            insert into ownership_relation_conflict
                (id, organization_id, owner_id, parcel_id, source, observed_active,
                 relation_revision, status, observed_at, created_at)
            values (?, ?, ?, ?, 'METSIS_PORTFOLIO', false, 1, 'OPEN', cast(? as timestamptz), current_timestamp)
            """, conflictId, ORG, OWNER_A, PARCEL, Instant.now());

        var resolved = service.resolveConflict(
            conflictId,
            OwnerParcelRelationService.Resolution.ACCEPT_PROVIDER,
            "Registry evidence was verified by operator");

        assertThat(resolved.active()).isFalse();
        assertThat(resolved.source()).isEqualTo("METSIS_PORTFOLIO");
        assertThat(resolved.manualOverride()).isFalse();
        assertThat(auditCount(OWNER_A, PARCEL)).isEqualTo(2);
        assertThat(conflictStatus(conflictId)).isEqualTo("RESOLVED");
    }

    private void insertOrganization(UUID id, String name) {
        dsl.execute("insert into organization (id, name) values (?, ?)", id, name);
    }

    private void insertOwner(UUID orgId, UUID id, String name, String code) {
        dsl.execute("""
            insert into owner (id, organization_id, display_name, personal_or_registry_code)
            values (?, ?, ?, ?)
            """, id, orgId, name, code);
    }

    private void insertParcel(UUID orgId, UUID id, String code, String name) {
        dsl.execute("""
            insert into land_parcel (id, organization_id, cadastral_code, name, area_hectares)
            values (?, ?, ?, ?, 10.5000)
            """, id, orgId, code, name);
    }

    private void insertRelation(UUID orgId, UUID ownerId, UUID parcelId, boolean active,
                                String source, boolean manualOverride, int revision) {
        dsl.execute("""
            insert into owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, manual_override,
                 manual_revision, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
            """, orgId, ownerId, parcelId, source, active, manualOverride, revision);
    }

    private int activeRelationCount(UUID parcelId) {
        return dsl.fetchOne("""
            select count(*) as cnt from owner_parcel_relation
             where organization_id=? and parcel_id=? and active=true
            """, ORG, parcelId).get("cnt", Integer.class);
    }

    private Boolean relationActive(UUID ownerId, UUID parcelId) {
        return dsl.fetchOne("""
            select active from owner_parcel_relation
             where organization_id=? and owner_id=? and parcel_id=?
            """, ORG, ownerId, parcelId).get("active", Boolean.class);
    }

    private int auditCount(UUID ownerId, UUID parcelId) {
        return dsl.fetchOne("""
            select count(*) as cnt from owner_parcel_relation_audit
             where organization_id=? and owner_id=? and parcel_id=?
            """, ORG, ownerId, parcelId).get("cnt", Integer.class);
    }

    private String conflictStatus(UUID conflictId) {
        return dsl.fetchOne("select status from ownership_relation_conflict where id=?", conflictId)
            .get("status", String.class);
    }

    private void resetSchema() {
        dsl.execute("drop table if exists ownership_relation_conflict cascade");
        dsl.execute("drop table if exists owner_parcel_relation_audit cascade");
        dsl.execute("drop table if exists owner_parcel_relation cascade");
        dsl.execute("drop table if exists land_parcel cascade");
        dsl.execute("drop table if exists owner cascade");
        dsl.execute("drop table if exists organization cascade");

        dsl.execute("create table organization (id uuid primary key, name varchar(180) not null)");
        dsl.execute("""
            create table owner (
                id uuid not null,
                organization_id uuid not null,
                display_name varchar(300) not null,
                personal_or_registry_code varchar(64) not null,
                primary key (organization_id, id)
            )
            """);
        dsl.execute("""
            create table land_parcel (
                id uuid not null,
                organization_id uuid not null,
                cadastral_code varchar(100) not null,
                name varchar(300),
                area_hectares numeric(14,4),
                primary key (organization_id, id),
                unique (organization_id, cadastral_code)
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation (
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                source varchar(80) not null,
                source_external_key varchar(255),
                active boolean not null,
                source_seen_at timestamptz,
                deactivated_at timestamptz,
                created_at timestamptz not null default current_timestamp,
                updated_at timestamptz not null default current_timestamp,
                manual_override boolean not null default false,
                manual_actor_subject varchar(255),
                manual_reason text,
                manual_changed_at timestamptz,
                manual_revision integer not null default 0,
                primary key (organization_id, owner_id, parcel_id)
            )
            """);
        dsl.execute("""
            create table owner_parcel_relation_audit (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                action varchar(40) not null,
                relation_source varchar(32) not null,
                actor_subject varchar(255) not null,
                reason text not null,
                payload jsonb not null default '{}'::jsonb,
                created_at timestamptz not null default current_timestamp
            )
            """);
        dsl.execute("""
            create table ownership_relation_conflict (
                id uuid primary key,
                organization_id uuid not null,
                owner_id uuid not null,
                parcel_id uuid not null,
                source varchar(32) not null,
                observed_active boolean not null,
                relation_revision integer not null,
                status varchar(16) not null default 'OPEN',
                observed_at timestamptz not null,
                created_at timestamptz not null default current_timestamp,
                resolved_at timestamptz,
                resolved_by_subject varchar(255),
                resolution varchar(32),
                resolution_reason text,
                unique (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
            )
            """);
    }
}
