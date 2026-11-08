package dev.rehearsal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductionBoundaryTests {
    @Test void disabledExecutionDoesNotContactTheWorkerCluster() throws Exception {
        var sandbox = mock(Sandbox.class);
        var policy = new ExecutionPolicy(production(),false,mock(JdbcTemplate.class),sandbox);
        policy.verifyExecutionBoundary().run(null);
        assertEquals(Status.UP,policy.health().getStatus());
        assertEquals(503,assertThrows(ResponseStatusException.class,policy::requireEnabled).getStatusCode().value());
        verifyNoInteractions(sandbox);
    }

    @Test void guardRejectsTheSamePhysicalClusterEvenWhenPortsDiffer() throws SQLException {
        var metadata = mock(JdbcTemplate.class);
        var broker = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(broker.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(2)).thenReturn(true);
        when(rows.getString(1)).thenReturn("91954");
        when(metadata.queryForObject(anyString(),eq(String.class))).thenReturn("91954");
        var policy = new ExecutionPolicy(production(),true,metadata,mock(Sandbox.class));
        assertThrows(SQLException.class,() -> policy.verify(broker));

        when(metadata.queryForObject(anyString(),eq(String.class))).thenReturn("27498");
        assertDoesNotThrow(() -> policy.verify(broker));
    }

    @Test void duplicateBackendCannotRecoverTheRunningBackendsQueue() throws SQLException {
        var dataSource = mock(DataSource.class);
        var store = mock(WorkspaceStore.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        var lease = new MetadataLease(dataSource,store);
        assertThrows(IllegalStateException.class,() -> lease.run(null));
        verifyNoInteractions(store);
        verify(connection).close();
        assertEquals(Status.DOWN,lease.health().getStatus());
    }

    private StandardEnvironment production() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }
}
