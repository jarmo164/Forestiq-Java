package ee.forestiq.security;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the current organization to every PostgreSQL connection before it is
 * handed to JPA/jOOQ. The value is session-scoped so it also covers code paths
 * that do not open an explicit Spring transaction.
 *
 * The value is overwritten on every checkout and cleared best-effort on close,
 * preventing tenant state from leaking between pooled connection borrowers.
 */
final class TenantAwareDataSource extends DelegatingDataSource {

    TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scope(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scope(super.getConnection(username, password));
    }

    private Connection scope(Connection connection) throws SQLException {
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            return connection;
        }

        applyOrganization(connection);

        return (Connection) Proxy.newProxyInstance(
            TenantAwareDataSource.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    try {
                        clearOrganization(connection);
                    } finally {
                        connection.close();
                    }
                    return null;
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getTargetException();
                }
            }
        );
    }

    private static void applyOrganization(Connection connection) throws SQLException {
        String organizationId = TenantDatabaseContext.currentOrganizationId()
            .map(Object::toString)
            .orElse("");
        try (PreparedStatement statement =
                 connection.prepareStatement("select set_config('app.organization_id', ?, false)")) {
            statement.setString(1, organizationId);
            statement.execute();
        }
    }

    private static void clearOrganization(Connection connection) {
        try (PreparedStatement statement =
                 connection.prepareStatement("select set_config('app.organization_id', '', false)")) {
            statement.execute();
        } catch (SQLException ignored) {
            // A failed transaction can reject cleanup SQL. Every subsequent
            // checkout overwrites the setting before application SQL can run.
        }
    }
}
