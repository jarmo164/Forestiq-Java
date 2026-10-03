package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class IntegrationExecutionRouterTest {

    @Test
    void allRuntimeRoleAllowsInlineExecution() {
        IntegrationExecutionRouter router = new IntegrationExecutionRouter(() -> "all", () -> "");

        assertThat(router.inlineExecutionEnabled()).isTrue();
    }

    @Test
    void apiRuntimeRoleDisablesInlineExecution() {
        IntegrationExecutionRouter router = new IntegrationExecutionRouter(() -> "api", () -> "");

        assertThat(router.inlineExecutionEnabled()).isFalse();
    }

    @Test
    void pythonRoutedKeysAreExcludedFromJavaClaimSet() {
        AtomicReference<String> pythonKeys = new AtomicReference<>(" cadastre_public_data, inheritance_notices ");

        IntegrationExecutionRouter router = new IntegrationExecutionRouter(() -> "all", pythonKeys::get);

        assertThat(router.routedToPython(IntegrationJobKey.CADASTRE_PUBLIC_DATA)).isTrue();
        assertThat(router.javaClaimableJobKeys(List.of(
            IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            IntegrationJobKey.FOREST_NOTIFICATIONS,
            IntegrationJobKey.INHERITANCE_NOTICES)))
            .containsExactly("FOREST_NOTIFICATIONS");
    }
}
