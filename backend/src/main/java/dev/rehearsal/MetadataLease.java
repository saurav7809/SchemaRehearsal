package dev.rehearsal;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Holds a PostgreSQL session lock for the lifetime of the sole queue owner. */
@Component("metadataLease")
@Order(Ordered.HIGHEST_PRECEDENCE)
class MetadataLease implements ApplicationRunner, HealthIndicator {
    private static final long LOCK = 787713936295L;
    private final DataSource dataSource;
    private final WorkspaceStore store;
    private Connection connection;

    MetadataLease(DataSource dataSource, WorkspaceStore store) {
        this.dataSource = dataSource;
        this.store = store;
    }

    @Override public synchronized void run(ApplicationArguments args) throws SQLException {
        connection = dataSource.getConnection();
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("select pg_try_advisory_lock(" + LOCK + ")")) {
            if (!row.next() || !row.getBoolean(1))
                throw new IllegalStateException("Another SchemaRehearsal instance owns this metadata database.");
            store.markInterruptedRuns();
        } catch (SQLException | RuntimeException e) {
            close();
            throw e;
        }
    }

    synchronized void requireActive() {
        if (!active())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The rehearsal queue is unavailable. Contact the deployment operator.");
    }

    private boolean active() {
        try { return connection != null && connection.isValid(2); }
        catch (SQLException e) { return false; }
    }

    @Override public synchronized Health health() {
        return active() ? Health.up().build() : Health.down().build();
    }

    @PreDestroy synchronized void close() {
        if (connection == null) return;
        try (var owned = connection; var statement = owned.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("select pg_advisory_unlock(" + LOCK + ")");
        } catch (SQLException ignored) {
            // Pool shutdown closes the underlying session and releases its lock.
        } finally {
            connection = null;
        }
    }
}
