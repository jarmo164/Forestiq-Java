package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PostgisContainerTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @Test
    void supportsPostgisExtension() throws Exception {
        try (var connection = postgres.createConnection("" ); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            ResultSet result = statement.executeQuery("SELECT ST_Area(ST_GeomFromText('POLYGON((0 0, 1 0, 1 1, 0 0))', 4326)::geography) > 0");
            result.next();
            assertThat(result.getBoolean(1)).isTrue();
        }
    }

    @Test
    void boundGeoJsonNeedsExplicitJsonCast() throws Exception {
        String geoJson = "{\"type\":\"Polygon\",\"coordinates\":[[[24.48,58.19],[24.48,58.20],[24.49,58.20],[24.48,58.19]]]}";
        try (var connection = postgres.createConnection(""); var create = connection.createStatement()) {
            create.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            try (var statement = connection.prepareStatement(
                    "SELECT ST_GeometryType(ST_SetSRID(ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(?::json))), 4326))")) {
                statement.setString(1, geoJson);
                ResultSet result = statement.executeQuery();
                result.next();
                assertThat(result.getString(1)).isEqualTo("ST_Polygon");
            }
        }
    }
}
