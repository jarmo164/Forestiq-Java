package ee.forestiq.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AdminUserAssignmentGuardPostgisTest {

    private static final DockerImageName POSTGIS = DockerImageName
        .parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS)
        .withDatabaseName("forestiq_guard_test")
        .withUsername("forestiq")
        .withPassword("forestiq-test");

    @BeforeAll
    static void migrateRealSchema() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @Test
    void followingImpactUsesOwnerOrganizationOnFlywayMigratedPostgisSchema() throws Exception {
        UUID activeOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID activeOwner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        String subject = "subject-" + UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.execute("insert into organization (id, name) values (?, ?)", activeOrg, "Guard active " + activeOrg);
            dsl.execute("insert into organization (id, name) values (?, ?)", otherOrg, "Guard other " + otherOrg);
            dsl.execute("insert into owner (id, organization_id, display_name, personal_or_registry_code) values (?, ?, ?, ?)",
                activeOwner, activeOrg, "Active owner", "A-" + activeOwner.toString().replace("-", "").substring(0, 30));
            dsl.execute("insert into owner (id, organization_id, display_name, personal_or_registry_code) values (?, ?, ?, ?)",
                otherOwner, otherOrg, "Other owner", "B-" + otherOwner.toString().replace("-", "").substring(0, 30));
            dsl.execute("insert into owner_following (owner_id, user_subject) values (?, ?)", activeOwner, subject);
            dsl.execute("insert into owner_following (owner_id, user_subject) values (?, ?)", otherOwner, subject);

            AdminUserAssignmentGuard guard = new AdminUserAssignmentGuard(dsl);

            var activeImpact = guard.impact(activeOrg, subject);
            assertThat(activeImpact.followedOwners()).isOne();
            assertThat(activeImpact.blockingAssignments()).isOne();

            var otherImpact = guard.impact(otherOrg, subject);
            assertThat(otherImpact.followedOwners()).isOne();
            assertThat(otherImpact.blockingAssignments()).isOne();

            assertThatThrownBy(() -> guard.requireSafeToDelete(activeOrg, subject))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT")
                .hasMessageContaining("followedOwners=1");
        }
    }
}
