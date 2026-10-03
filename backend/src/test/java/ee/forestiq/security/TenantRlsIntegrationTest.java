package ee.forestiq.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class TenantRlsIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    private static final UUID ORG_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORG_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OWNER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OWNER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    private static final List<String> RLS_TABLES = List.of(
        "owner",
        "land_parcel",
        "owner_parcel_relation",
        "owner_activity",
        "owner_parcel_relation_audit",
        "deal_case",
        "deal_case_event",
        "contract",
        "contract_history",
        "saved_workbasket",
        "saved_workbasket_event"
    );

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection connection = superuser();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO organization (id, name)
                VALUES
                    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'RLS org A'),
                    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'RLS org B')
                ON CONFLICT (id) DO NOTHING
                """);
            statement.execute("""
                INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, status)
                VALUES
                    ('aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Tenant A owner', 'RLS-A', 'NEW'),
                    ('bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Tenant B owner', 'RLS-B', 'NEW')
                ON CONFLICT (id) DO NOTHING
                """);
            statement.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forestiq_rls_test') THEN
                        CREATE ROLE forestiq_rls_test LOGIN PASSWORD 'forestiq-rls-test'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
                    END IF;
                END
                $$
                """);
            statement.execute("GRANT USAGE ON SCHEMA public, app TO forestiq_rls_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE owner TO forestiq_rls_test");
            statement.execute("GRANT SELECT ON TABLE owner_parcel_relation TO forestiq_rls_test");
            statement.execute("GRANT EXECUTE ON FUNCTION app.current_organization_id() TO forestiq_rls_test");
        }
    }

    @Test
    void highRiskTablesHaveForcedRowLevelSecurity() throws Exception {
        try (Connection connection = superuser();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relname = ANY (?)
                 ORDER BY c.relname
                 """)) {
            statement.setArray(1, connection.createArrayOf("text", RLS_TABLES.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                int seen = 0;
                while (rows.next()) {
                    seen++;
                    assertThat(rows.getBoolean("relrowsecurity"))
                        .as(rows.getString("relname") + " ENABLE RLS")
                        .isTrue();
                    assertThat(rows.getBoolean("relforcerowsecurity"))
                        .as(rows.getString("relname") + " FORCE RLS")
                        .isTrue();
                }
                assertThat(seen).isEqualTo(RLS_TABLES.size());
            }
        }
    }

    @Test
    void apiAndIngestRolesCannotBypassRls() throws Exception {
        try (Connection connection = superuser();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT rolname, rolsuper, rolbypassrls
                 FROM pg_roles
                 WHERE rolname IN ('forestiq_api', 'forestiq_ingest')
                 ORDER BY rolname
                 """);
             ResultSet rows = statement.executeQuery()) {
            int seen = 0;
            while (rows.next()) {
                seen++;
                assertThat(rows.getBoolean("rolsuper")).isFalse();
                assertThat(rows.getBoolean("rolbypassrls")).isFalse();
            }
            assertThat(seen).isEqualTo(2);
        }
    }

    @Test
    void missingTenantContextReturnsNoRows() throws Exception {
        try (Connection connection = tenantConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM owner")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test
    void tenantContextProtectsReadsAndWritesEvenWithoutOrganizationPredicate() throws Exception {
        try (Connection connection = tenantConnection()) {
            setOrganization(connection, ORG_A);

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM owner WHERE id IN (?, ?) ORDER BY id")) {
                statement.setObject(1, OWNER_A);
                statement.setObject(2, OWNER_B);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1, UUID.class)).isEqualTo(OWNER_A);
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE owner SET display_name = 'cross-tenant-write' WHERE id = ?")) {
                statement.setObject(1, OWNER_B);
                assertThat(statement.executeUpdate()).isZero();
            }

            assertThatThrownBy(() -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO owner (organization_id, display_name, personal_or_registry_code, status)
                    VALUES (?, 'Rejected cross tenant owner', 'RLS-CROSS', 'NEW')
                    """)) {
                    statement.setObject(1, ORG_B);
                    statement.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void tenantInventoryContainsEveryOrganizationColumnTable() throws Exception {
        try (Connection connection = superuser();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT
                     (SELECT count(*)
                        FROM information_schema.columns
                       WHERE column_name = 'organization_id'
                         AND table_schema IN ('public', 'core', 'app', 'ingest')) AS schema_count,
                     (SELECT count(*) FROM app.tenant_table_inventory) AS inventory_count
                 """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt("inventory_count")).isEqualTo(rows.getInt("schema_count"));
        }
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection tenantConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "forestiq_rls_test", "forestiq-rls-test");
    }

    private static void setOrganization(Connection connection, UUID organizationId) throws SQLException {
        try (PreparedStatement statement =
                 connection.prepareStatement("SELECT set_config('app.organization_id', ?, false)")) {
            statement.setString(1, organizationId.toString());
            statement.execute();
        }
    }
}
