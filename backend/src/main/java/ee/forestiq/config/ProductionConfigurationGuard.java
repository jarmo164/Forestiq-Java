package ee.forestiq.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"prod", "production"})
public class ProductionConfigurationGuard implements ApplicationRunner {

    private final Environment environment;

    public ProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    void validate() {
        List<String> problems = new ArrayList<>();
        requireSecret(problems, "spring.datasource.password", "forestiq");
        requireSecret(problems, "forestiq.storage.access-key", "forestiq-minio");
        requireSecret(problems, "forestiq.storage.secret-key", "forestiq-minio-secret");
        requireSecret(problems, "forestiq.keycloak-admin.client-secret", null);
        rejectLocal(problems, "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        rejectLocal(problems, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        rejectLocal(problems, "forestiq.cors.allowed-origins");
        rejectLocal(problems, "forestiq.storage.endpoint");
        rejectDevelopmentRealm(problems, "forestiq.keycloak-admin.realm");

        requireWhenEnabled(problems, "forestiq.inheritance-source.auto-refresh-enabled",
            "forestiq.inheritance-source.uri-template", "forestiq.inheritance-source.user-agent",
            "forestiq.inheritance-source.zone-id");
        requireWhenEnabled(problems, "forestiq.metsis-portfolio.auto-sync-enabled",
            "forestiq.metsis-portfolio.uri", "forestiq.metsis-portfolio.authorization", "forestiq.metsis-portfolio.organization-id");
        requireWhenEnabled(problems, "forestiq.weasel.auto-refresh-enabled",
            "forestiq.weasel.base-url", "forestiq.weasel.access-token", "forestiq.weasel.organization-id");

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production configuration is incomplete: " + String.join("; ", problems));
        }
    }

    private void requireWhenEnabled(List<String> problems, String enabledProperty, String... requiredProperties) {
        if (!environment.getProperty(enabledProperty, Boolean.class, false)) return;
        for (String property : requiredProperties) {
            String value = environment.getProperty(property, "");
            if (value.isBlank()) problems.add(property + " must be configured when " + enabledProperty + " is enabled");
        }
    }

    private void requireSecret(List<String> problems, String property, String forbiddenValue) {
        String value = environment.getProperty(property, "");
        if (value.isBlank() || (forbiddenValue != null && forbiddenValue.equals(value))) {
            problems.add(property + " must be set to a non-development secret");
        }
    }

    private void rejectLocal(List<String> problems, String property) {
        String value = environment.getProperty(property, "");
        String lower = value.toLowerCase();
        if (value.isBlank() || lower.contains("localhost") || lower.contains("127.0.0.1")
            || lower.contains("forestiq-dev")) {
            problems.add(property + " must not point at local development infrastructure");
        }
    }

    private void rejectDevelopmentRealm(List<String> problems, String property) {
        String value = environment.getProperty(property, "");
        if (value.isBlank() || value.toLowerCase().contains("dev")) {
            problems.add(property + " must identify a non-development Keycloak realm");
        }
    }
}
