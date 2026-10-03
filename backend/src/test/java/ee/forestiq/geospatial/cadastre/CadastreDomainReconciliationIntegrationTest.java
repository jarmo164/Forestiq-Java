package ee.forestiq.geospatial.cadastre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cache.type=simple",
        "spring.quartz.auto-startup=false",
        "forestiq.cors.allowed-origins=http://localhost:3000",
        "forestiq.scheduling.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("core")
class CadastreDomainReconciliationIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");
    private static final UUID OWNER_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");
    private static final UUID PARCEL_A = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");
    private static final UUID PARCEL_B = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc2");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockBean private JwtDecoder jwtDecoder;

    @Autowired private GeospatialSourceGenerationRepository generations;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void reset() {
        dsl.execute("""
            TRUNCATE TABLE
                geospatial_cadastre_domain_event,
                geospatial_cadastre_replacement_candidate,
                geospatial_cadastre_domain_link,
                geospatial_cadastre_reconciliation,
                geospatial_cadastre_parcel,
                geospatial_generation_event,
                geospatial_source_current,
                geospatial_generation,
                geospatial_source_run,
                geospatial_source,
                owner_parcel_relation,
                land_parcel,
                owner,
                organization
            RESTART IDENTITY CASCADE
            """);
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, 'Reconciliation test')", ORG_ID);
        dsl.execute("""
            INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, status)
            VALUES (?, ?, 'Owner one', '39001010001', 'NEW'),
                   (?, ?, 'Owner two', '39001010002', 'NEW')
            """, OWNER_1, ORG_ID, OWNER_2, ORG_ID);
        dsl.execute("""
            INSERT INTO geospatial_source
                (source_key, provider, dataset_key, display_name, source_kind,
                 expected_source_srid, canonical_srid)
            VALUES ('MARU_CADASTRE', 'MAA_JA_RUUMIAMET', 'cadastre',
                    'Maa- ja Ruumiamet — kataster', 'SNAPSHOT', 3301, 3301)
            """);
    }

    @Test
    void geometryRefreshAndRollbackKeepStableParcelAndAllOwners() {
        addBusinessParcel(PARCEL_A, "11101:001:0001");
        addOwnerRelation(OWNER_1, PARCEL_A);
        addOwnerRelation(OWNER_2, PARCEL_A);

        UUID first = createValidatedGeneration(
            "sha256:g1", "11101:001:0001", "001", 500_000, 6_500_000, 500_100, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", first, "integration-test", "first publication");

        assertThat(linkedParcelId("11101:001:0001")).isEqualTo(PARCEL_A);
        assertThat(linkStatus(PARCEL_A)).isEqualTo("ACTIVE");
        assertThat(activeOwnerRelations(PARCEL_A)).isEqualTo(2);

        UUID second = createValidatedGeneration(
            "sha256:g2", "11101:001:0001", "001", 500_000, 6_500_000, 500_130, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", second, "integration-test", "geometry refresh");

        assertThat(linkedParcelId("11101:001:0001")).isEqualTo(PARCEL_A);
        assertThat(linkStatus(PARCEL_A)).isEqualTo("ACTIVE");
        assertThat(linkChange(PARCEL_A)).isEqualTo("GEOMETRY_CHANGED");
        assertThat(activeOwnerRelations(PARCEL_A)).isEqualTo(2);
        assertThat(reconciliationMetric(second, "geometry_changed_count")).isEqualTo(1L);
        assertThat(reconciliationMetric(second, "domain_link_failure_count")).isZero();

        generations.rollbackToRetired(
            "MARU_CADASTRE", first, "integration-test", "verify domain-safe rollback");

        assertThat(generations.currentGenerationId("MARU_CADASTRE")).isEqualTo(first);
        assertThat(linkedParcelId("11101:001:0001")).isEqualTo(PARCEL_A);
        assertThat(linkStatus(PARCEL_A)).isEqualTo("ACTIVE");
        assertThat(activeOwnerRelations(PARCEL_A)).isEqualTo(2);
        assertThat(reconciliationMode(first)).isEqualTo("ROLLBACK");
    }

    @Test
    void removedParcelStaysLinkedAndGetsDeterministicReplacementCandidate() {
        addBusinessParcel(PARCEL_A, "22202:002:0001");
        addOwnerRelation(OWNER_1, PARCEL_A);

        UUID first = createValidatedGeneration(
            "sha256:old", "22202:002:0001", "old", 500_000, 6_500_000, 500_100, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", first, "integration-test", "old parcel");

        UUID second = createValidatedGeneration(
            "sha256:new", "22202:002:0099", "new", 500_010, 6_500_000, 500_110, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", second, "integration-test", "replacement-like parcel");

        assertThat(linkedParcelId("22202:002:0001")).isEqualTo(PARCEL_A);
        assertThat(linkStatus(PARCEL_A)).isEqualTo("REPLACEMENT_CANDIDATE");
        assertThat(linkChange(PARCEL_A)).isEqualTo("REPLACEMENT_CANDIDATE");
        assertThat(activeOwnerRelations(PARCEL_A)).isEqualTo(1);
        assertThat(replacementCode(second, PARCEL_A)).isEqualTo("22202:002:0099");
        assertThat(replacementRank(second, PARCEL_A)).isEqualTo(1);
        assertThat(reconciliationMetric(second, "added_count")).isEqualTo(1L);
        assertThat(reconciliationMetric(second, "removed_count")).isEqualTo(1L);
        assertThat(reconciliationMetric(second, "previous_row_count")).isEqualTo(1L);
        assertThat(reconciliationMetric(second, "target_row_count")).isEqualTo(1L);
    }

    @Test
    void firstPublicationMarksMissingBusinessParcelUnresolvedNotRemoved() {
        addBusinessParcel(PARCEL_A, "33303:003:0001");

        UUID first = createValidatedGeneration(
            "sha256:first", "33303:003:9999", "source-only", 500_000, 6_500_000, 500_100, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", first, "integration-test", "baseline publication");

        assertThat(linkedParcelId("33303:003:0001")).isEqualTo(PARCEL_A);
        assertThat(linkStatus(PARCEL_A)).isEqualTo("UNRESOLVED");
        assertThat(linkChange(PARCEL_A)).isEqualTo("UNRESOLVED");
        assertThat(reconciliationMetric(first, "unresolved_domain_parcel_count")).isEqualTo(1L);
    }

    @Test
    void reconciliationFailureRollsBackCurrentPointerChange() {
        addBusinessParcel(PARCEL_A, "44404:004:0001");
        addBusinessParcel(PARCEL_B, "44404:004:0002");

        UUID first = createValidatedGeneration(
            "sha256:good", "44404:004:0001", "good", 500_000, 6_500_000, 500_100, 6_500_100);
        generations.publishValidated("MARU_CADASTRE", first, "integration-test", "good publication");

        // Simulate corrupt pre-existing link state that violates the org+code invariant on the
        // next upsert. V53 now creates domain-link rows for both business parcels, so remove
        // both generated rows before deliberately re-inserting PARCEL_B with PARCEL_A's code.
        dsl.execute("DELETE FROM geospatial_cadastre_domain_link WHERE parcel_id IN (?, ?)", PARCEL_A, PARCEL_B);
        dsl.execute("""
            INSERT INTO geospatial_cadastre_domain_link
                (parcel_id, organization_id, cadastral_code, reconciled_generation_id,
                 source_status, last_change_type)
            VALUES (?, ?, '44404:004:0001', ?, 'UNRESOLVED', 'UNRESOLVED')
            """, PARCEL_B, ORG_ID, first);

        UUID second = createValidatedGeneration(
            "sha256:blocked", "44404:004:0001", "changed", 500_000, 6_500_000, 500_120, 6_500_100);

        assertThatThrownBy(() -> generations.publishValidated(
            "MARU_CADASTRE", second, "integration-test", "must fail closed"))
            .isInstanceOf(RuntimeException.class);

        assertThat(generations.currentGenerationId("MARU_CADASTRE")).isEqualTo(first);
        assertThat(generationStatus(first)).isEqualTo("PUBLISHED");
        assertThat(generationStatus(second)).isEqualTo("VALIDATED");
    }

    private void addBusinessParcel(UUID parcelId, String code) {
        dsl.execute("""
            INSERT INTO land_parcel
                (id, organization_id, owner_id, cadastral_code, area_hectares)
            VALUES (?, ?, NULL, ?, 1.0000)
            """, parcelId, ORG_ID, code);
    }

    private void addOwnerRelation(UUID ownerId, UUID parcelId) {
        dsl.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at)
            VALUES (?, ?, ?, 'MANUAL', TRUE, CURRENT_TIMESTAMP)
            """, ORG_ID, ownerId, parcelId);
    }

    private UUID createValidatedGeneration(String checksum,
                                           String cadastralCode,
                                           String marker,
                                           double minX,
                                           double minY,
                                           double maxX,
                                           double maxY) {
        UUID runId = generations.startRun("MARU_CADASTRE", "MANUAL");
        generations.recordDownloadedArtifact(runId, checksum, checksum, 100L);
        UUID generationId = generations.createStagingGeneration(
            runId,
            "MARU_CADASTRE",
            LocalDate.of(2026, 9, 6),
            checksum,
            checksum,
            100L,
            "schema-v1",
            3301,
            3301);
        dsl.execute("""
            INSERT INTO geospatial_cadastre_parcel
                (generation_id, cadastral_code, source_attributes, source_area_m2, geom)
            VALUES (?, ?, jsonb_build_object('hkood', ?, 'eksport', '2026-09-06'),
                    ST_Area(ST_MakeEnvelope(?, ?, ?, ?, 3301)),
                    ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 3301)))
            """,
            generationId, cadastralCode, marker,
            minX, minY, maxX, maxY,
            minX, minY, maxX, maxY);
        generations.markValidated(generationId, 1, 0, 0, "{}");
        return generationId;
    }

    private UUID linkedParcelId(String cadastralCode) {
        return jdbcTemplate.queryForObject(
            "SELECT parcel_id FROM geospatial_cadastre_domain_link WHERE organization_id = ? AND cadastral_code = ?",
            UUID.class,
            ORG_ID,
            cadastralCode);
    }

    private String linkStatus(UUID parcelId) {
        return jdbcTemplate.queryForObject(
            "SELECT source_status FROM geospatial_cadastre_domain_link WHERE parcel_id = ?",
            String.class,
            parcelId);
    }

    private String linkChange(UUID parcelId) {
        return jdbcTemplate.queryForObject(
            "SELECT last_change_type FROM geospatial_cadastre_domain_link WHERE parcel_id = ?",
            String.class,
            parcelId);
    }

    private int activeOwnerRelations(UUID parcelId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM owner_parcel_relation WHERE parcel_id = ? AND active = TRUE",
            Integer.class,
            parcelId);
        return count == null ? 0 : count;
    }

    private long reconciliationMetric(UUID generationId, String metricColumn) {
        if (!metricColumn.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unsafe metric column");
        }
        Long value = jdbcTemplate.queryForObject(
            "SELECT " + metricColumn + " FROM geospatial_cadastre_reconciliation "
                + "WHERE target_generation_id = ? ORDER BY created_at DESC LIMIT 1",
            Long.class,
            generationId);
        return value == null ? 0L : value;
    }

    private String reconciliationMode(UUID generationId) {
        return jdbcTemplate.queryForObject(
            "SELECT mode FROM geospatial_cadastre_reconciliation "
                + "WHERE target_generation_id = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            generationId);
    }

    private String replacementCode(UUID generationId, UUID parcelId) {
        return jdbcTemplate.queryForObject("""
            SELECT rc.candidate_cadastral_code
              FROM geospatial_cadastre_replacement_candidate rc
              JOIN geospatial_cadastre_reconciliation r ON r.id = rc.reconciliation_id
             WHERE r.target_generation_id = ? AND rc.parcel_id = ?
             ORDER BY r.created_at DESC, rc.candidate_rank
             LIMIT 1
            """, String.class, generationId, parcelId);
    }

    private int replacementRank(UUID generationId, UUID parcelId) {
        Integer rank = jdbcTemplate.queryForObject("""
            SELECT rc.candidate_rank
              FROM geospatial_cadastre_replacement_candidate rc
              JOIN geospatial_cadastre_reconciliation r ON r.id = rc.reconciliation_id
             WHERE r.target_generation_id = ? AND rc.parcel_id = ?
             ORDER BY r.created_at DESC, rc.candidate_rank
             LIMIT 1
            """, Integer.class, generationId, parcelId);
        return rank == null ? 0 : rank;
    }

    private String generationStatus(UUID generationId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM geospatial_generation WHERE id = ?",
            String.class,
            generationId);
    }
}
