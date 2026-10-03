package ee.forestiq.integration;

import java.util.Map;

public record IntegrationJobResult(Outcome outcome, Map<String, Object> metrics, String message) {
    public IntegrationJobResult {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        message = message == null ? "" : message;
    }

    public static IntegrationJobResult success(Map<String, Object> metrics, String message) {
        return new IntegrationJobResult(Outcome.SUCCEEDED, metrics, message);
    }

    public static IntegrationJobResult partial(Map<String, Object> metrics, String message) {
        return new IntegrationJobResult(Outcome.PARTIAL, metrics, message);
    }

    public enum Outcome { SUCCEEDED, PARTIAL }
}

