package dev.rehearsal;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LockImpactTests {
    private static final String RUN="sr_run_00000000000000000000000000000001";
    private static final String LOCK="LOCK TABLE ONLY public.\"users\" IN ROW EXCLUSIVE MODE";
    private final Sandbox sandbox=mock(Sandbox.class);
    private final Connection connection=mock(Connection.class);
    private final PreparedStatement catalog=mock(PreparedStatement.class);
    private final ResultSet relations=mock(ResultSet.class);
    private final Statement command=mock(Statement.class);
    private final LockImpact observer=new LockImpact(sandbox);

    @Test void rejectsInvalidIdentifierBeforeConnecting() {
        try (var session=observer.start(RUN,"public.users")) {
            var evidence=session.finish();
            assertEquals("UNAVAILABLE",evidence.status());
            assertEquals(0,evidence.attempts());
        }
        verifyNoInteractions(sandbox);
    }

    @Test void verifiesTheTableInTheStartingCloneAndClosesWhenAbsent() throws SQLException {
        prepare(false);

        try (var session=observer.start(RUN,"users")) {
            var evidence=session.finish();
            assertEquals("UNAVAILABLE",evidence.status());
            assertTrue(evidence.note().contains("absent"));
        }

        verify(catalog).setString(1,"users");
        verify(connection).close();
        verify(connection,never()).createStatement();
    }

    @Test void measuresImmediateWriteIntentAndRollsBackEachSuccessfulAttempt() throws Exception {
        prepare(true);
        var sampled=new CountDownLatch(1);
        when(command.execute(LOCK + " NOWAIT")).thenAnswer(invocation -> {
            sampled.countDown();
            return false;
        });

        try (var session=observer.start(RUN,"users")) {
            assertTrue(sampled.await(5,TimeUnit.SECONDS));
            var evidence=session.finish();
            assertSame(evidence,session.finish());
            assertEquals("MEASURED",evidence.status());
            assertTrue(evidence.attempts()>0);
            assertEquals(0,evidence.blockedAttempts());
            assertEquals(0,evidence.maxWaitMs());
            verify(connection,times(evidence.attempts())).rollback();
        }

        verify(connection).setAutoCommit(false);
        verify(command,never()).execute(LOCK);
        verify(connection).close();
    }

    @Test void retriesNowaitConflictsAndCountsLockTimeouts() throws Exception {
        prepare(true);
        var waited=new CountDownLatch(1);
        when(command.execute(LOCK + " NOWAIT")).thenThrow(new SQLException("blocked","55P03"));
        when(command.execute(LOCK)).thenAnswer(invocation -> {
            waited.countDown();
            throw new SQLException("lock timeout","55P03");
        });

        try (var session=observer.start(RUN,"users")) {
            assertTrue(waited.await(5,TimeUnit.SECONDS));
            var evidence=session.finish();
            assertEquals("MEASURED",evidence.status());
            assertTrue(evidence.blockedAttempts()>0);
            assertEquals(evidence.attempts(),evidence.blockedAttempts());
            assertEquals(evidence.blockedAttempts(),evidence.timedOutAttempts());
            verify(command,times(evidence.attempts())).execute("SET LOCAL lock_timeout = '500ms'");
            verify(command,times(evidence.attempts())).execute("SET LOCAL statement_timeout = '1000ms'");
            verify(connection,times(2*evidence.attempts())).rollback();
        }

        verify(connection).close();
    }

    @Test void unexpectedSqlErrorLeavesTheMeasurementUnavailableAndHidesRawDetails() throws Exception {
        prepare(true);
        var failed=new CountDownLatch(1);
        when(command.execute(LOCK + " NOWAIT")).thenAnswer(invocation -> {
            failed.countDown();
            throw new SQLException("private database detail","42P01");
        });

        try (var session=observer.start(RUN,"users")) {
            assertTrue(failed.await(5,TimeUnit.SECONDS));
            var evidence=session.finish();
            assertEquals("UNAVAILABLE",evidence.status());
            assertFalse(evidence.note().contains("private database detail"));
        }

        verify(connection).rollback();
        verify(connection).close();
        verify(command,never()).execute(LOCK);
    }

    @Test void interruptingDrainAbortsTheConnectionAndPreservesInterruptStatus() throws Exception {
        prepare(true);
        var entered=new CountDownLatch(1);
        var aborted=new CountDownLatch(1);
        var closed=new CountDownLatch(1);
        when(command.execute(LOCK + " NOWAIT")).thenAnswer(invocation -> {
            entered.countDown();
            aborted.await(5,TimeUnit.SECONDS);
            throw new SQLException("observer aborted","08006");
        });
        doAnswer(invocation -> { aborted.countDown(); return null; }).when(connection).abort(any(Executor.class));
        doAnswer(invocation -> { closed.countDown(); return null; }).when(connection).close();

        try (var session=observer.start(RUN,"users")) {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                assertEquals("UNAVAILABLE",session.finish().status());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }

        assertTrue(aborted.await(5,TimeUnit.SECONDS));
        assertTrue(closed.await(5,TimeUnit.SECONDS));
    }

    private void prepare(boolean present) throws SQLException {
        when(sandbox.worker(RUN)).thenReturn(connection);
        when(connection.prepareStatement(contains("c.relname=? and c.relkind in ('r','p')"))).thenReturn(catalog);
        when(catalog.executeQuery()).thenReturn(relations);
        when(relations.next()).thenReturn(present);
        when(connection.createStatement()).thenReturn(command);
    }
}
