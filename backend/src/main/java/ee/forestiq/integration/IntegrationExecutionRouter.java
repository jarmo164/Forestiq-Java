package ee.forestiq.integration;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class IntegrationExecutionRouter {
    private final Supplier<String> runtimeRole;
    private final Supplier<String> pythonJobKeys;

    IntegrationExecutionRouter(Supplier<String> runtimeRole, Supplier<String> pythonJobKeys) {
        this.runtimeRole = runtimeRole;
        this.pythonJobKeys = pythonJobKeys;
    }

    boolean inlineExecutionEnabled() {
        String role = runtimeRole.get();
        return role == null || role.isBlank() || "all".equalsIgnoreCase(role);
    }

    boolean routedToPython(IntegrationJobKey key) {
        return pythonJobKeySet().contains(key.name());
    }

    Set<String> javaClaimableJobKeys(Collection<IntegrationJobKey> handlerKeys) {
        Set<String> pythonKeys = pythonJobKeySet();
        return handlerKeys.stream()
            .map(IntegrationJobKey::name)
            .filter(key -> !pythonKeys.contains(key))
            .collect(Collectors.toUnmodifiableSet());
    }

    Set<String> pythonJobKeySet() {
        String configuredKeys = pythonJobKeys.get();
        if (configuredKeys == null || configuredKeys.isBlank()) return Set.of();
        return Arrays.stream(configuredKeys.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> value.toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }
}
