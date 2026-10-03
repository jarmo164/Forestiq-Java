package ee.forestiq.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ComposeEnvironmentContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void legacySpringProviderVariablesRemainDocumentedWithoutLeakingIntoApiRuntime() throws Exception {
        String compose = Files.readString(ROOT.resolve("compose.yaml"));
        String env = Files.readString(ROOT.resolve(".env.example"));
        String application = Files.readString(ROOT.resolve("backend/src/main/resources/application.yml"));

        List<String> legacyProviderVariables = List.of(
            "REGISTRY_AUTO_REFRESH_ENABLED", "REGISTRY_AUTO_REFRESH_DELAY_MS", "REGISTRY_AUTO_REFRESH_BATCH_SIZE",
            "REGISTRY_CADASTRE_MAX_AGE_HOURS", "REGISTRY_FOREST_MAX_AGE_HOURS",
            "REGISTRY_AUDIT_RETENTION_DAYS", "REGISTRY_AUDIT_TROUBLESHOOTING_RETENTION_HOURS",
            "REGISTRY_AUDIT_MAX_STORED_PAYLOAD_BYTES", "REGISTRY_AUDIT_MAX_DIAGNOSTIC_SAMPLE_BYTES",
            "REGISTRY_AUDIT_FULL_PAYLOAD_ENABLED", "REGISTRY_AUDIT_CLEANUP_DELAY_MS",
            "MAAAMET_WFS_URL", "MAAAMET_WFS_TIMEOUT_SECONDS", "MAAAMET_WFS_MAX_ATTEMPTS",
            "METSAREGISTER_WFS_URL", "METSAREGISTER_WFS_TIMEOUT_SECONDS", "METSAREGISTER_WFS_MAX_ATTEMPTS",
            "METSAREGISTER_WFS_PAGE_SIZE", "METSAREGISTER_WFS_MAX_PAGES_PER_QUERY", "METSAREGISTER_WFS_SORT_FIELD",
            "METSAREGISTER_WFS_BULK_BATCH_SIZE", "METSAREGISTER_WFS_BULK_PAGES_PER_RUN",
            "METSAREGISTER_WFS_MIN_REQUEST_INTERVAL_MS",
            "RIK_KINNISTUSRAAMAT_WSDL", "RIK_KINNISTUSRAAMAT_USERNAME", "RIK_KINNISTUSRAAMAT_PASSWORD",
            "INHERITANCE_SOURCE_URI_TEMPLATE", "INHERITANCE_SOURCE_USER_AGENT", "INHERITANCE_SOURCE_ZONE_ID",
            "INHERITANCE_SOURCE_CONNECT_TIMEOUT_SECONDS", "INHERITANCE_SOURCE_TIMEOUT_SECONDS",
            "INHERITANCE_SOURCE_AUTO_REFRESH_ENABLED", "INHERITANCE_SOURCE_AUTO_REFRESH_DELAY_MS",
            "INHERITANCE_SOURCE_AUTO_REFRESH_BATCH_SIZE", "INHERITANCE_SOURCE_MAX_BACKFILL_DAYS");

        for (String variable : legacyProviderVariables) {
            assertThat(env).as(".env.example documents %s", variable).contains(variable + "=");
            assertThat(application).as("Spring binds %s", variable).contains(variable);
        }

        String apiSection = compose.substring(
            compose.indexOf("\n  api:"),
            compose.indexOf("\n\n  ingest-scheduler:")
        );
        for (String variable : legacyProviderVariables) {
            assertThat(apiSection)
                .as("API runtime must not receive legacy provider variable %s", variable)
                .doesNotContain(variable + ":");
        }

        assertThat(env).contains("INHERITANCE_SOURCE_AUTO_REFRESH_DELAY_MS=1800000");
        assertThat(application).contains("INHERITANCE_SOURCE_AUTO_REFRESH_DELAY_MS:1800000");
    }

    @Test
    void productionComposeEnablesProductionProfileAndContainsNoDevelopmentKeycloak() throws Exception {
        String local = Files.readString(ROOT.resolve("compose.yaml"));
        String production = Files.readString(ROOT.resolve("compose.prod.yaml"));

        assertThat(local).contains("LOCAL DEVELOPMENT ONLY").contains("start-dev");
        assertThat(production).contains("SPRING_PROFILES_ACTIVE: production")
            .doesNotContain("start-dev")
            .doesNotContain("forestiq-dev")
            .contains("KEYCLOAK_ISSUER_URI: ${KEYCLOAK_ISSUER_URI:?")
            .contains("S3_ENDPOINT: ${S3_ENDPOINT:?")
            .contains("DATABASE_PASSWORD: ${DATABASE_PASSWORD:?");
    }

    @Test
    void onlySupportedPostgresDevelopmentPathIsPostgres17Postgis() throws Exception {
        String compose = Files.readString(ROOT.resolve("compose.yaml"));

        assertThat(compose).contains("postgis/postgis:17-3.5")
            .contains("platform: ${POSTGRES_PLATFORM:-linux/amd64}");
        assertThat(Files.exists(ROOT.resolve("dockerfiles/postgres-dev/Dockerfile"))).isFalse();
    }
}
