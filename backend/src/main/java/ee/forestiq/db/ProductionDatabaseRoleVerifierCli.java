package ee.forestiq.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProductionDatabaseRoleVerifierCli {
    private static final Map<String, String> LOGIN_TO_GROUP = new LinkedHashMap<>();
    static {
        LOGIN_TO_GROUP.put("forestiq_api_login", "forestiq_api");
        LOGIN_TO_GROUP.put("forestiq_ingest_login", "forestiq_ingest");
    }

    private ProductionDatabaseRoleVerifierCli() {}

    public static void main(String[] args) throws Exception {
        String url = normalize(required("FLYWAY_DATABASE_URL"));
        try (Connection connection = DriverManager.getConnection(
            url,
            required("FLYWAY_DATABASE_USERNAME"),
            required("FLYWAY_DATABASE_PASSWORD"))) {

            for (Map.Entry<String, String> entry : LOGIN_TO_GROUP.entrySet()) {
                requireSafeLogin(connection, entry.getKey());
                requireMembership(connection, entry.getKey(), entry.getValue());
            }
            requireSafeLogin(connection, "forestiq_keycloak_login");
            requireKeycloakSchemaOwner(connection);
        }
        System.out.println("Production database runtime-role boundary is valid.");
    }

    private static void requireSafeLogin(Connection connection, String role) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "select rolcanlogin, rolsuper, rolbypassrls from pg_roles where rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Missing production login role: " + role);
                if (!result.getBoolean("rolcanlogin")) throw new IllegalStateException(role + " must be LOGIN");
                if (result.getBoolean("rolsuper")) throw new IllegalStateException(role + " must not be SUPERUSER");
                if (result.getBoolean("rolbypassrls")) throw new IllegalStateException(role + " must not BYPASSRLS");
            }
        }
    }

    private static void requireMembership(Connection connection, String member, String group) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from pg_auth_members m " +
            "join pg_roles parent on parent.oid = m.roleid " +
            "join pg_roles child on child.oid = m.member " +
            "where parent.rolname = ? and child.rolname = ?")) {
            statement.setString(1, group);
            statement.setString(2, member);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException(member + " must inherit " + group);
            }
        }
    }

    private static void requireKeycloakSchemaOwner(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "select r.rolname from pg_namespace n join pg_roles r on r.oid = n.nspowner where n.nspname = 'keycloak'")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Missing keycloak schema");
                if (!"forestiq_keycloak_login".equals(result.getString(1))) {
                    throw new IllegalStateException("keycloak schema must be owned by forestiq_keycloak_login");
                }
            }
        }
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
}
