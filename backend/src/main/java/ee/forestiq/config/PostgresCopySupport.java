package ee.forestiq.config;

import java.io.IOException;
import java.io.Reader;
import java.sql.SQLException;

import org.jooq.DSLContext;
import org.postgresql.PGConnection;

/** PostgreSQL-specific bulk copy adapter kept at the infrastructure boundary. */
public final class PostgresCopySupport {

    private PostgresCopySupport() { }

    public static long copyIn(DSLContext dsl, String copySql, Reader data) {
        return dsl.connectionResult(connection -> {
            try {
                PGConnection pg = connection.unwrap(PGConnection.class);
                return pg.getCopyAPI().copyIn(copySql, data);
            } catch (SQLException | IOException exception) {
                throw new IllegalStateException("PostgreSQL COPY failed", exception);
            }
        });
    }
}
