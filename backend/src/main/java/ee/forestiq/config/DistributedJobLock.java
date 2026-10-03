package ee.forestiq.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * Holds a PostgreSQL session advisory lock on one dedicated connection while a
 * recurring job executes. This gives every @Scheduled worker a single-instance
 * guarantee across all application replicas without introducing a second
 * scheduler/coordination system.
 */
@Component
public class DistributedJobLock {
    private final DataSource dataSource;

    public DistributedJobLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> LockedResult<T> tryRun(String lockName, Supplier<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, lockName)) return new LockedResult<>(false, null);
            try {
                return new LockedResult<>(true, work.get());
            } finally {
                unlock(connection, lockName);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Distributed job lock failed: " + lockName, exception);
        }
    }

    public boolean tryRun(String lockName, Runnable work) {
        return tryRun(lockName, () -> {
            work.run();
            return Boolean.TRUE;
        }).acquired();
    }

    private static boolean tryLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, lockName);
            statement.executeQuery().close();
        } catch (SQLException exception) {
            // The connection is about to close; PostgreSQL releases all session locks on close.
        }
    }

    public record LockedResult<T>(boolean acquired, T value) { }
}
