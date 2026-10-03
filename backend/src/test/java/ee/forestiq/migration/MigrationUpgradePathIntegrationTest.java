package ee.forestiq.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MigrationUpgradePathIntegrationTest {
    private static final String FRESH_DB = "forestiq_fresh";
    private static final String UPGRADE_DB = "forestiq_upgrade";

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("postgres")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @BeforeAll
    static void createTestDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + FRESH_DB);
            statement.execute("CREATE DATABASE " + UPGRADE_DB);
        }
    }

    @Test
    void freshDatabaseMigratesToLatestAndValidates() {
        Flyway flyway = flyway(FRESH_DB, null);
        assertTrue(flyway.migrate().success);
        flyway.validate();
        assertEquals(latestVersion(flyway), flyway.info().current().getVersion());
    }

    @Test
    void previousReleaseSchemaUpgradesToLatest() {
        Flyway inspector = flyway(UPGRADE_DB, null);
        List<MigrationVersion> versions = versionedMigrations(inspector);
        assertTrue(versions.size() >= 2, "At least two versioned migrations are required");

        MigrationVersion previous = versions.get(versions.size() - 2);
        MigrationVersion latest = versions.get(versions.size() - 1);

        Flyway previousFlyway = flyway(UPGRADE_DB, previous);
        assertTrue(previousFlyway.migrate().success);
        assertEquals(previous, previousFlyway.info().current().getVersion());

        Flyway currentFlyway = flyway(UPGRADE_DB, null);
        assertTrue(currentFlyway.migrate().success);
        currentFlyway.validate();
        assertEquals(latest, currentFlyway.info().current().getVersion());
    }

    private Flyway flyway(String database, MigrationVersion target) {
        String jdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .validateOnMigrate(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private MigrationVersion latestVersion(Flyway flyway) {
        List<MigrationVersion> versions = versionedMigrations(flyway);
        return versions.get(versions.size() - 1);
    }

    private List<MigrationVersion> versionedMigrations(Flyway flyway) {
        return Arrays.stream(flyway.info().all())
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }
}
