package ee.forestiq.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationGuardTest {

    @Test
    void rejectsDevelopmentDefaultsInProductionProfile() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.password", "forestiq")
            .withProperty("forestiq.storage.access-key", "forestiq-minio")
            .withProperty("forestiq.storage.secret-key", "forestiq-minio-secret")
            .withProperty("forestiq.storage.endpoint", "http://localhost:9000")
            .withProperty("forestiq.keycloak-admin.client-secret", "")
            .withProperty("forestiq.keycloak-admin.realm", "forestiq-dev")
            .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:8180/realms/forestiq-dev")
            .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "http://localhost:8180/realms/forestiq-dev/protocol/openid-connect/certs")
            .withProperty("forestiq.cors.allowed-origins", "http://localhost:3000");

        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.password")
            .hasMessageContaining("forestiq.storage.endpoint")
            .hasMessageContaining("forestiq.keycloak-admin.realm")
            .hasMessageContaining("forestiq.cors.allowed-origins");
    }

    @Test
    void acceptsExplicitProductionCoreConfigurationWhenOptionalWorkersAreDisabled() {
        MockEnvironment environment = validCoreEnvironment();

        assertThatCode(() -> new ProductionConfigurationGuard(environment).validate())
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsEnabledExternalWorkerWithoutItsRequiredCredentials() {
        MockEnvironment environment = validCoreEnvironment()
            .withProperty("forestiq.weasel.auto-refresh-enabled", "true")
            .withProperty("forestiq.weasel.base-url", "https://weasel.internal.example")
            .withProperty("forestiq.weasel.organization-id", "11111111-1111-1111-1111-111111111111");

        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forestiq.weasel.access-token")
            .hasMessageContaining("forestiq.weasel.auto-refresh-enabled");
    }

    @Test
    void rejectsEnabledInheritanceWorkerWithoutDirectFeedConfiguration() {
        MockEnvironment environment = validCoreEnvironment()
            .withProperty("forestiq.inheritance-source.auto-refresh-enabled", "true");

        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forestiq.inheritance-source.uri-template")
            .hasMessageContaining("forestiq.inheritance-source.auto-refresh-enabled");
    }

    private static MockEnvironment validCoreEnvironment() {
        return new MockEnvironment()
            .withProperty("spring.datasource.password", "prod-db-secret")
            .withProperty("forestiq.storage.access-key", "prod-storage-user")
            .withProperty("forestiq.storage.secret-key", "prod-storage-secret")
            .withProperty("forestiq.storage.endpoint", "https://s3.example")
            .withProperty("forestiq.keycloak-admin.client-secret", "prod-keycloak-secret")
            .withProperty("forestiq.keycloak-admin.realm", "forestiq")
            .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://identity.example/realms/forestiq")
            .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "https://identity.example/realms/forestiq/protocol/openid-connect/certs")
            .withProperty("forestiq.cors.allowed-origins", "https://forestiq.example");
    }
}
