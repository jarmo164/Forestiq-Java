package ee.forestiq.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public final class H2JooqTestDatabase implements AutoCloseable {

    private final Connection connection;
    private final DSLContext dsl;

    private H2JooqTestDatabase(Connection connection) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
    }

    public static H2JooqTestDatabase create(String name) throws SQLException {
        Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa",
            ""
        );
        return new H2JooqTestDatabase(connection);
    }

    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
