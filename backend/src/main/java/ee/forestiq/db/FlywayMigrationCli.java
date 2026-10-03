package ee.forestiq.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

public final class FlywayMigrationCli {
    private FlywayMigrationCli() {}

    public static void main(String[] args) {
        String url = required("FLYWAY_DATABASE_URL");
        String user = required("FLYWAY_DATABASE_USERNAME");
        String password = required("FLYWAY_DATABASE_PASSWORD");
        if (url.startsWith("postgresql://")) {
            url = "jdbc:" + url;
        } else if (url.startsWith("postgres://")) {
            url = "jdbc:postgresql://" + url.substring("postgres://".length());
        }
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("FLYWAY_DATABASE_URL must be a PostgreSQL JDBC/libpq URL");
        }

        Flyway flyway = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .validateOnMigrate(true)
            .load();

        MigrateResult result = flyway.migrate();
        flyway.validate();
        MigrationInfo current = flyway.info().current();
        String version = current == null || current.getVersion() == null
            ? "none"
            : current.getVersion().getVersion();

        System.out.printf(
            "ForestIQ migration complete: version=%s executed=%d success=%s%n",
            version,
            result.migrationsExecuted,
            result.success
        );
        if (!result.success) {
            throw new IllegalStateException("Flyway reported unsuccessful migration");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
