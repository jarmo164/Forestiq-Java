package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MetsaregisterWfsProjectionIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID ORG_NEAR = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID ORG_FAR = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID PARCEL_NEAR = UUID.fromString("62000000-0000-0000-0000-000000000001");
    private static final UUID PARCEL_FAR = UUID.fromString("62000000-0000-0000-0000-000000000002");
    private static final String CADASTRAL_CODE = "77777:001:0001";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl.configuration().set(SQLDialect.POSTGRES);

        dsl.execute("DELETE FROM metsaregister_wfs_feature");
        dsl.execute("UPDATE metsaregister_wfs_layer SET current_generation_id=NULL, current_row_count=0, state='PENDING_SCHEMA'");
        dsl.execute("DELETE FROM metsaregister_wfs_generation");
        dsl.execute("DELETE FROM land_parcel WHERE organization_id IN (?, ?)", ORG_NEAR, ORG_FAR);
        dsl.execute("DELETE FROM organization WHERE id IN (?, ?)", ORG_NEAR, ORG_FAR);

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, 'Projection near'), (?, 'Projection far')",
            ORG_NEAR, ORG_FAR);
        dsl.execute("""
            INSERT INTO land_parcel
                (id, organization_id, cadastral_code, area_hectares, boundary)
            VALUES
                (?, ?, ?, 10.0,
                 ST_GeomFromText('POLYGON((24.69 59.39,24.72 59.39,24.72 59.42,24.69 59.42,24.69 59.39))', 4326)),
                (?, ?, ?, 10.0,
                 ST_GeomFromText('POLYGON((26.00 58.00,26.03 58.00,26.03 58.03,26.00 58.03,26.00 58.00))', 4326))
            """, PARCEL_NEAR, ORG_NEAR, CADASTRAL_CODE, PARCEL_FAR, ORG_FAR, CADASTRAL_CODE);
    }

    @Test
    void notificationProjectionUnifiesCurrentAndArchiveWithoutTenantCopies() {
        seedFeature("metsaregister:teatis", "notice-current",
            "{\"sys_id\":1,\"katastri_nr\":\"77777:001:0001\",\"teatise_nr\":\"MT-1\",\"staatus\":\"KEHTIV\"}", null);
        seedFeature("metsaregister:teatis_arhiiv", "notice-archive",
            "{\"sys_id\":2,\"katastritunnus\":\"77777:001:0001\",\"teatis_nr\":\"MT-0\"}", null);

        var rows = dsl.fetch("""
            SELECT archived, notification_key, cadastral_code
              FROM metsaregister_wfs_notification
             ORDER BY archived
            """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("archived", Boolean.class)).isFalse();
        assertThat(rows.get(0).get("notification_key", String.class)).isEqualTo("MT-1");
        assertThat(rows.get(1).get("archived", Boolean.class)).isTrue();
        assertThat(rows.get(1).get("notification_key", String.class)).isEqualTo("MT-0");
        assertThat(rows).allSatisfy(row ->
            assertThat(row.get("cadastral_code", String.class)).isEqualTo(CADASTRAL_CODE));

        Integer organizationColumns = dsl.fetchOne("""
            SELECT count(*)::int AS c
              FROM information_schema.columns
             WHERE table_schema='public'
               AND table_name='metsaregister_wfs_feature'
               AND column_name='organization_id'
            """).get("c", Integer.class);
        assertThat(organizationColumns).isZero();
    }

    @Test
    void compartmentProjectionJoinsElementsPeculiaritiesAndDamages() {
        seedFeature("metsaregister:eraldis", "compartment-10",
            "{\"sys_id\":10,\"katastri_nr\":\"77777:001:0001\"}",
            "ST_GeomFromText('POLYGON((24.700 59.400,24.710 59.400,24.710 59.410,24.700 59.410,24.700 59.400))',4326)");
        seedFeature("metsaregister:eraldis_element", "element-1",
            "{\"sys_id\":101,\"eraldis_id\":\"10\",\"puuliik\":\"KU\"}", null);
        seedFeature("metsaregister:isearasused", "10|A",
            "{\"eraldis_id\":\"10\",\"kood\":\"A\"}", null);
        seedFeature("metsaregister:kahjustused", "10|K|1",
            "{\"eraldis_id\":\"10\",\"kood\":\"K\",\"kahjustuse_pohjus\":\"TORM\",\"kahjustuse_aste\":\"2\"}", null);

        var detail = dsl.fetchOne("""
            SELECT compartment_id, cadastral_code,
                   jsonb_array_length(elements) AS element_count,
                   jsonb_array_length(peculiarities) AS peculiarity_count,
                   jsonb_array_length(damages) AS damage_count
              FROM metsaregister_wfs_compartment_detail
             WHERE compartment_id='10'
            """);

        assertThat(detail).isNotNull();
        assertThat(detail.get("cadastral_code", String.class)).isEqualTo(CADASTRAL_CODE);
        assertThat(detail.get("element_count", Integer.class)).isEqualTo(1);
        assertThat(detail.get("peculiarity_count", Integer.class)).isEqualTo(1);
        assertThat(detail.get("damage_count", Integer.class)).isEqualTo(1);
    }

    @Test
    void classifiersAreJoinableThroughStableTypeAndCodeContract() {
        seedFeature("metsaregister:kl_puuliik", "KU",
            "{\"kood\":\"KU\",\"nimetus\":\"Kuusk\"}", null);
        seedFeature("metsaregister:kl_kasvukoht", "JK",
            "{\"kood\":\"JK\",\"nimi\":\"Jänesekapsa\"}", null);

        var rows = dsl.fetch("""
            SELECT classifier_type, code, label
              FROM metsaregister_wfs_classifier
             ORDER BY classifier_type
            """);

        assertThat(rows).extracting(row -> row.get("classifier_type", String.class))
            .containsExactly("KASVUKOHT", "PUULIIK");
        assertThat(rows).extracting(row -> row.get("code", String.class))
            .containsExactly("JK", "KU");
    }

    @Test
    void parcelSpatialProjectionScopesByTenantAndUsesGlobalCurrentGeneration() {
        seedFeature("metsaregister:natura_2000_alad", "natura-near", "{\"sys_id\":501}",
            "ST_GeomFromText('POLYGON((24.700 59.400,24.705 59.400,24.705 59.405,24.700 59.405,24.700 59.400))',4326)");
        seedFeature("metsaregister:natura_2000_alad", "natura-far", "{\"sys_id\":502}",
            "ST_GeomFromText('POLYGON((25.500 58.500,25.505 58.500,25.505 58.505,25.500 58.505,25.500 58.500))',4326)");

        var near = dsl.fetch("""
            SELECT feature_key
              FROM metsaregister_wfs_features_for_parcel(?, ?, ARRAY['metsaregister:natura_2000_alad'])
            """, ORG_NEAR, CADASTRAL_CODE);
        var farTenant = dsl.fetch("""
            SELECT feature_key
              FROM metsaregister_wfs_features_for_parcel(?, ?, ARRAY['metsaregister:natura_2000_alad'])
            """, ORG_FAR, CADASTRAL_CODE);

        assertThat(near).extracting(row -> row.get("feature_key", String.class))
            .containsExactly("natura-near");
        assertThat(farTenant).isEmpty();
        assertThat(dsl.fetchCount(DSL.table("metsaregister_wfs_feature"))).isEqualTo(2);
    }

    @Test
    void failedRefreshStateDoesNotHideLastKnownGoodPublishedGeneration() {
        seedFeature("metsaregister:teatis", "last-good",
            "{\"sys_id\":9,\"katastri_nr\":\"77777:001:0001\"}", null);
        dsl.execute("UPDATE metsaregister_wfs_layer SET state='FAILED' WHERE layer_name='metsaregister:teatis'");

        Integer count = dsl.fetchOne("""
            SELECT count(*)::int AS c
              FROM metsaregister_wfs_current_feature
             WHERE layer_name='metsaregister:teatis'
            """).get("c", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    private void seedFeature(String layerName, String featureKey, String propertiesJson, String geometryExpression) {
        UUID generation = currentGeneration(layerName);
        if (generation == null) {
            generation = UUID.randomUUID();
            dsl.execute("""
                INSERT INTO metsaregister_wfs_generation
                    (id, layer_name, state, next_start_index, pages_processed, rows_staged, completed_at)
                VALUES (?, ?, 'PUBLISHED', 1, 1, 0, CURRENT_TIMESTAMP)
                """, generation, layerName);
            dsl.execute("""
                UPDATE metsaregister_wfs_layer
                   SET current_generation_id=?, state='HEALTHY'
                 WHERE layer_name=?
                """, generation, layerName);
        }

        if (geometryExpression == null) {
            dsl.execute("""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                VALUES (?, ?, ?, ?, repeat('a',64), CAST(? AS jsonb), NULL)
                """, generation, layerName, featureKey, featureKey, propertiesJson);
        } else {
            dsl.execute("""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                VALUES (?, ?, ?, ?, repeat('a',64), CAST(? AS jsonb), ST_Transform(%s, 3301))
                """.formatted(geometryExpression), generation, layerName, featureKey, featureKey, propertiesJson);
        }
        dsl.execute("""
            UPDATE metsaregister_wfs_generation
               SET rows_staged = rows_staged + 1, next_start_index = next_start_index + 1
             WHERE id=?
            """, generation);
        dsl.execute("""
            UPDATE metsaregister_wfs_layer
               SET current_row_count = current_row_count + 1
             WHERE layer_name=?
            """, layerName);
    }

    private UUID currentGeneration(String layerName) {
        return dsl.fetchOne("""
            SELECT current_generation_id
              FROM metsaregister_wfs_layer
             WHERE layer_name=?
            """, layerName).get("current_generation_id", UUID.class);
    }
}
