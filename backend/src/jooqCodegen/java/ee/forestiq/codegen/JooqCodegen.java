package ee.forestiq.codegen;

import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.ForcedType;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Strategy;
import org.jooq.meta.jaxb.Target;

public final class JooqCodegen {

    private static final String POSTGIS_IMAGE = "postgis/postgis:17-3.5";
    private static final String DATABASE = "forestiq";
    private static final String USERNAME = "forestiq";
    private static final String PASSWORD = "forestiq";

    private JooqCodegen() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: JooqCodegen <flyway-migration-dir> <generated-output-dir>");
        }

        String containerName = "forestiq-jooq-codegen-" + UUID.randomUUID();
        run("docker", "run", "--rm", "-d",
            "--name", containerName,
            "-e", "POSTGRES_DB=" + DATABASE,
            "-e", "POSTGRES_USER=" + USERNAME,
            "-e", "POSTGRES_PASSWORD=" + PASSWORD,
            "-p", "127.0.0.1::5432",
            POSTGIS_IMAGE);

        try {
            String jdbcUrl = jdbcUrl(containerName);
            waitUntilReady(jdbcUrl);
            Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("filesystem:" + args[0])
                .load()
                .migrate();

            GenerationTool.generate(new org.jooq.meta.jaxb.Configuration()
                .withJdbc(new Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(jdbcUrl)
                    .withUser(USERNAME)
                    .withPassword(PASSWORD))
                .withGenerator(new Generator()
                    .withName("org.jooq.codegen.JavaGenerator")
                    .withStrategy(new Strategy().withName("org.jooq.codegen.DefaultGeneratorStrategy"))
                    .withDatabase(new Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public")
                        .withIncludes(".*")
                        // Operational staging/control-plane tables stay adapter-owned. Domain columns
                        // on owner and inheritance_case remain generated so Flyway and jOOQ cannot drift.
                        .withIncludeExcludeColumns(true)
                        .withExcludes(String.join("|",
                            "flyway_schema_history",
                            "geography_columns",
                            "geometry_columns",
                            "geometry_dump",
                            // Operational integration/staging projections are deliberately accessed
                            // through their adapters and kept out of the generated domain model.
                            "integration_sync_run",
                            "owner_parcel_snapshot_row",
                            "owner_snapshot_row",
                            "inheritance_notice_import",
                            "registry_bulk_sync_checkpoint",
                            "registry_bulk_sync_feature",
                            "external_dataset_snapshot_run",
                            "external_dataset_feature",
                            "external_dataset_current",
                            "external_inheritance_notice",
                            "pgp_.*",
                            "postgis_.*",
                            "raster_columns",
                            "raster_overviews",
                            "spatial_ref_sys",
                            "st_.*",
                            "valid_detail"))
                        .withForcedTypes(new ForcedType()
                            .withUserType("org.locationtech.jts.geom.Geometry")
                            .withIncludeTypes("geometry")))
                    .withGenerate(new Generate()
                        .withDeprecated(false)
                        .withRecords(true)
                        .withFluentSetters(true)
                        .withJavaTimeTypes(true)
                        .withRoutines(false))
                    .withTarget(new Target()
                        .withPackageName("ee.forestiq.jooq")
                        .withDirectory(args[1]))));
        } finally {
            runIgnoringFailure("docker", "stop", containerName);
        }
    }

    private static String jdbcUrl(String containerName) throws IOException, InterruptedException {
        String hostPort = run("docker", "port", containerName, "5432/tcp").trim();
        int separator = hostPort.lastIndexOf(':');
        if (separator < 0) {
            throw new IllegalStateException("Could not parse mapped PostgreSQL port from: " + hostPort);
        }
        return "jdbc:postgresql://127.0.0.1:" + hostPort.substring(separator + 1) + "/" + DATABASE;
    }

    private static void waitUntilReady(String jdbcUrl) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        SQLException lastError = null;
        while (System.nanoTime() < deadline) {
            try (var ignored = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
                return;
            } catch (SQLException ex) {
                lastError = ex;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("PostGIS container did not become ready in time", lastError);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private static void runIgnoringFailure(String... command) {
        try {
            run(command);
        } catch (Exception ignored) {
            // Best-effort cleanup for the temporary code generation container.
        }
    }
}
