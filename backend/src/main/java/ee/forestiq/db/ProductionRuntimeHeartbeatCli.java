package ee.forestiq.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ProductionRuntimeHeartbeatCli {
    private static final Set<String> REQUIRED_COMPONENTS = Set.of("scheduler", "worker");

    private ProductionRuntimeHeartbeatCli() {}

    public static void main(String[] args) throws Exception {
        String url = normalize(required("FLYWAY_DATABASE_URL"));
        long maxAgeSeconds = Long.parseLong(System.getenv().getOrDefault(
            "FORESTIQ_RUNTIME_HEARTBEAT_MAX_AGE_SECONDS", "120"));

        Map<String, Heartbeat> latest = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                url,
                required("FLYWAY_DATABASE_USERNAME"),
                required("FLYWAY_DATABASE_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(
                """
                SELECT DISTINCT ON (component)
                       component,
                       instance_id,
                       EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - last_seen_at))::bigint AS age_seconds
                  FROM ingestion_runtime_heartbeat
                 WHERE component IN ('scheduler', 'worker')
                 ORDER BY component, last_seen_at DESC
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                latest.put(result.getString("component"),
                    new Heartbeat(result.getString("instance_id"), result.getLong("age_seconds")));
            }
        }

        for (String component : REQUIRED_COMPONENTS) {
            Heartbeat heartbeat = latest.get(component);
            if (heartbeat == null) {
                throw new IllegalStateException("Missing production runtime heartbeat: " + component);
            }
            if (heartbeat.ageSeconds() < 0 || heartbeat.ageSeconds() > maxAgeSeconds) {
                throw new IllegalStateException(
                    component + " heartbeat is stale: ageSeconds=" + heartbeat.ageSeconds()
                        + " max=" + maxAgeSeconds);
            }
            System.out.printf(
                "runtime=%s instance=%s heartbeatAgeSeconds=%d%n",
                component,
                heartbeat.instanceId(),
                heartbeat.ageSeconds()
            );
        }
        System.out.println("Production scheduler/worker heartbeat check passed.");
    }

    private static String normalize(String url) {
        if (url.startsWith("postgresql://")) return "jdbc:" + url;
        if (url.startsWith("postgres://")) return "jdbc:postgresql://" + url.substring("postgres://".length());
        return url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private record Heartbeat(String instanceId, long ageSeconds) {}
}
