package dev.rehearsal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="SR_LOCK_TEST_PORT",matches="[0-9]{1,5}")
class LockImpactPostgresTest {
    @Test void measuresARealBoundedWriteLockWaitOnAnIsolatedClone() throws Exception {
        int port=Integer.parseInt(System.getenv("SR_LOCK_TEST_PORT"));
        var config=mock(DatabaseService.class);
        when(config.port()).thenReturn(port);
        when(config.url(anyString(),eq(port))).thenAnswer(call ->
            "jdbc:postgresql://127.0.0.1:" + port + "/" + call.getArgument(0) + "?connectTimeout=4&socketTimeout=5");
        when(config.brokerPassword()).thenReturn(System.getenv("SR_LOCK_TEST_BROKER_PASSWORD"));
        when(config.workerPassword()).thenReturn(System.getenv("SR_LOCK_TEST_WORKER_PASSWORD"));
        var sandbox=new Sandbox(config);
        var impact=new LockImpact(sandbox);
        String database=sandbox.name();
        try (var broker=sandbox.broker(); var brokerCommand=broker.createStatement()) {
            try (var lock=brokerCommand.executeQuery("select pg_try_advisory_lock(787713936294)")) {
                assertTrue(lock.next() && lock.getBoolean(1),"The rehearsal cluster must be idle for the test");
            }
            try {
                sandbox.provision(broker,database,"sr_fixture");
                try (var blocker=sandbox.worker(database); var statement=blocker.createStatement()) {
                    blocker.setAutoCommit(false);
                    statement.execute("LOCK TABLE ONLY public.users IN ACCESS EXCLUSIVE MODE");
                    try (var observation=impact.start(database,"users")) {
                        awaitWaitingProbe(statement);
                        // Leave the conflicting lock held beyond the probe's 500 ms lock timeout.
                        Thread.sleep(650);
                        var evidence=observation.finish();
                        assertEquals("MEASURED",evidence.status(),evidence.note());
                        assertTrue(evidence.blockedAttempts()>0);
                        assertTrue(evidence.timedOutAttempts()>0);
                        assertTrue(evidence.maxWaitMs()>=400);
                    } finally {
                        blocker.rollback();
                    }
                }
            } finally {
                sandbox.drop(broker,database);
            }
        }
    }

    private void awaitWaitingProbe(Statement observer) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        do {
            observer.execute("select pg_stat_clear_snapshot()");
            try (var row=observer.executeQuery("""
                select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid()
                    and datname=current_database() and wait_event_type='Lock'
                    and query ilike 'lock table only public."users" in row exclusive mode%')
                """)) {
                if (row.next() && row.getBoolean(1)) return;
            }
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        fail("PostgreSQL did not report a waiting ROW EXCLUSIVE probe");
    }
}
