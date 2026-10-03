package ee.forestiq.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CriticalPathRegistryTest {
    private static final Set<String> REQUIRED = Set.of(
        "owner_search",
        "parcel_detail",
        "protected_mvt",
        "durable_integration_enqueue",
        "registry_ingest_api_boundary");

    @Test
    void everyCriticalPathHasCharacterizationBenchmarkPlanAndFiniteMetricIdentity() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        JsonNode root = new ObjectMapper().readTree(
            Files.readString(repo.resolve("config/critical-paths.json")));

        assertThat(root.path("schemaVersion").asInt()).isGreaterThanOrEqualTo(1);
        Set<String> ids = new HashSet<>();
        Set<String> operations = new HashSet<>();
        for (JsonNode path : root.path("paths")) {
            String id = required(path, "id");
            assertThat(ids.add(id)).as("duplicate critical path %s", id).isTrue();

            String characterization = required(path, "characterizationTest");
            assertThat(Files.isRegularFile(repo.resolve(characterization)))
                .as("%s characterization test exists", id).isTrue();

            assertThat(required(path, "benchmark")).contains("#");
            assertThat(required(path, "queryPlan")).isNotBlank();
            assertThat(required(path, "invariant")).isNotBlank();

            String operation = required(path, "dbOperation");
            assertThat(operation).matches("[a-z0-9_]+");
            operations.add(operation);
        }

        assertThat(ids).containsAll(REQUIRED);
        assertThat(operations).containsExactlyInAnyOrder(
            "owner_search", "parcel_detail", "protected_mvt", "durable_queue", "stale_registry");
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        assertThat(value).as(field).isNotBlank();
        return value;
    }
}
