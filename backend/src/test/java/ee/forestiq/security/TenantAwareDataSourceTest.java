package ee.forestiq.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class TenantAwareDataSourceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writesJwtOrganizationToPostgresSessionAndClearsOnClose() throws Exception {
        UUID organizationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        when(delegate.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            jwt(Map.of("sub", "rls-test-user", "organization_id", organizationId.toString())),
            null
        ));

        TenantAwareDataSource dataSource = new TenantAwareDataSource(delegate);
        Connection scoped = dataSource.getConnection();

        verify(statement).setString(1, organizationId.toString());
        verify(statement).execute();

        scoped.close();

        verify(connection).prepareStatement("select set_config('app.organization_id', '', false)");
        verify(statement, atLeast(2)).execute();
        verify(connection).close();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
