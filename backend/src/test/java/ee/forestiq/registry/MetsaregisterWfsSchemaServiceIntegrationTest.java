package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
class MetsaregisterWfsSchemaServiceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    private DSLContext dsl;
    private MetsaregisterWfsMetadataClient metadata;
    private MetsaregisterWfsSchemaService schemas;

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
        metadata = mock(MetsaregisterWfsMetadataClient.class);
        schemas = new MetsaregisterWfsSchemaService(dsl, metadata, new ObjectMapper());
        dsl.deleteFrom(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq("metsaregister:test_without_key"))
            .execute();
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("provider_present"), Boolean.class), false)
            .set(field(name("schema_fingerprint"), String.class), (String) null)
            .set(field(name("schema_definition"), JSONB.class), JSONB.valueOf("{}"))
            .set(field(name("geometry_property"), String.class), (String) null)
            .set(field(name("geometry_type"), String.class), (String) null)
            .set(field(name("identity_property"), String.class), (String) null)
            .set(field(name("sort_property"), String.class), (String) null)
            .set(field(name("state"), String.class), "PENDING_SCHEMA")
            .set(field(name("last_error"), String.class), (String) null)
            .execute();
    }

    @Test
    void migratedCatalogTracksAllCurrentProviderFeatureTypesAndKeepsProtectedLayerDisabled() {
        int layers = dsl.fetchCount(table("metsaregister_wfs_layer"));
        assertThat(layers).isEqualTo(23);

        var protectedLayer = dsl.select(
                field(name("approved"), Boolean.class),
                field(name("enabled"), Boolean.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq("metsaregister:raadatud_metsateatised"))
            .fetchOne();

        assertThat(protectedLayer).isNotNull();
        assertThat(protectedLayer.value1()).isFalse();
        assertThat(protectedLayer.value2()).isFalse();
    }

    @Test
    void choosesLiveCompositeIdentityWhenLayerHasNoSysId() {
        prepare("metsaregister:isearasused");
        when(metadata.describeFeatureType("metsaregister:isearasused")).thenReturn(schema(
            "metsaregister:isearasused",
            property("eraldis_id"), property("kood"), geometry("geom")));

        var result = schemas.inspectApprovedLayers();

        assertThat(result.ready()).isEqualTo(1);
        assertThat(layerValue("metsaregister:isearasused", "identity_property")).isEqualTo("eraldis_id,kood");
        assertThat(layerValue("metsaregister:isearasused", "sort_property")).isEqualTo("eraldis_id,kood");
    }

    @Test
    void allowsProviderFeatureIdAsIdentityComponentWithoutSortingByIt() {
        prepare("metsaregister:kahjustused");
        when(metadata.describeFeatureType("metsaregister:kahjustused")).thenReturn(schema(
            "metsaregister:kahjustused",
            property("eraldis_id"), property("puuliigi_kood"), property("kvanus"), property("pohjuse_kood"),
            property("osakaal"), property("rinde_kood"), geometry("geom")));

        var result = schemas.inspectApprovedLayers();

        assertThat(result.ready()).isEqualTo(1);
        assertThat(layerValue("metsaregister:kahjustused", "identity_property"))
            .isEqualTo("eraldis_id,puuliigi_kood,kvanus,pohjuse_kood,osakaal,rinde_kood,__feature_id");
        assertThat(layerValue("metsaregister:kahjustused", "sort_property"))
            .isEqualTo("eraldis_id,puuliigi_kood,kvanus,pohjuse_kood,osakaal,rinde_kood");
    }

    @Test
    void acceptsNonSpatialClassifierWithApprovedKoodIdentity() {
        prepare("metsaregister:kl_puuliik");
        when(metadata.describeFeatureType("metsaregister:kl_puuliik")).thenReturn(schema(
            "metsaregister:kl_puuliik", property("kood"), property("nimetus")));

        var result = schemas.inspectApprovedLayers();

        assertThat(result.ready()).isEqualTo(1);
        assertThat(layerValue("metsaregister:kl_puuliik", "identity_property")).isEqualTo("kood");
        assertThat(layerValue("metsaregister:kl_puuliik", "geometry_property")).isNull();
    }

    @Test
    void missingDeterministicIdentityFailsClosedAsUnsupported() {
        dsl.insertInto(table("metsaregister_wfs_layer"))
            .columns(field("layer_name"), field("title"), field("approved"), field("enabled"),
                field("provider_present"), field("state"))
            .values("metsaregister:test_without_key", "No key", true, true, true, "PENDING_SCHEMA")
            .execute();
        when(metadata.describeFeatureType("metsaregister:test_without_key")).thenReturn(schema(
            "metsaregister:test_without_key", property("name"), geometry("geom")));

        var result = schemas.inspectApprovedLayers();

        assertThat(result.unsupported()).isEqualTo(1);
        assertThat(layerValue("metsaregister:test_without_key", "state")).isEqualTo("UNSUPPORTED");
        assertThat(layerValue("metsaregister:test_without_key", "identity_property")).isNull();
    }

    @Test
    void changedDescribeFeatureTypeFingerprintFailsClosedAsSchemaDrift() {
        prepare("metsaregister:eraldis");
        when(metadata.describeFeatureType("metsaregister:eraldis"))
            .thenReturn(schema("metsaregister:eraldis", property("sys_id"), geometry("geom")))
            .thenReturn(schema("metsaregister:eraldis", property("sys_id"), property("provider_new_field"), geometry("geom")));

        var first = schemas.inspectApprovedLayers();
        var second = schemas.inspectApprovedLayers();

        assertThat(first.ready()).isEqualTo(1);
        assertThat(second.schemaDrift()).isEqualTo(1);
        assertThat(layerValue("metsaregister:eraldis", "state")).isEqualTo("SCHEMA_DRIFT");
        assertThat(layerValue("metsaregister:eraldis", "last_error")).contains("DescribeFeatureType changed");
    }

    private void prepare(String layerName) {
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("provider_present"), Boolean.class), true)
            .set(field(name("approved"), Boolean.class), true)
            .set(field(name("enabled"), Boolean.class), true)
            .where(field(name("layer_name"), String.class).eq(layerName))
            .execute();
    }

    private String layerValue(String layerName, String column) {
        return dsl.select(field(name(column), String.class))
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("layer_name"), String.class).eq(layerName))
            .fetchOne(0, String.class);
    }

    private static MetsaregisterWfsMetadataClient.FeatureSchema schema(
        String typeName, MetsaregisterWfsMetadataClient.Property... properties
    ) {
        var list = List.of(properties);
        var geom = list.stream().filter(MetsaregisterWfsMetadataClient.Property::geometry).findFirst().orElse(null);
        return new MetsaregisterWfsMetadataClient.FeatureSchema(typeName, list,
            geom == null ? null : geom.name(), geom == null ? null : geom.type());
    }

    private static MetsaregisterWfsMetadataClient.Property property(String name) {
        return new MetsaregisterWfsMetadataClient.Property(name, "xsd:string", false);
    }

    private static MetsaregisterWfsMetadataClient.Property geometry(String name) {
        return new MetsaregisterWfsMetadataClient.Property(name, "gml:MultiSurfacePropertyType", true);
    }
}
