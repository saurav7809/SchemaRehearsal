package dev.rehearsal;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

@Component
class LockImpact {
    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String LOCK_UNAVAILABLE = "55P03";
    private static final long SAMPLE_DELAY = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long SAMPLE_BUDGET = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_ATTEMPTS = 600;

    record Evidence(String table,String status,int attempts,int blockedAttempts,int timedOutAttempts,
                    long maxWaitMs,String note) {}

    private final Sandbox sandbox;

    LockImpact(Sandbox sandbox) { this.sandbox=sandbox; }

    Session start(String database,String table) {
        if (table==null || !TABLE.matcher(table).matches())
            return new Session(unavailable(table,"Choose a lowercase table identifier in the public schema."));
        Connection connection=null;
        try {
            connection=sandbox.worker(database);
            connection.setNetworkTimeout(Runnable::run,1500);
            if (!exists(connection,table)) {
                closeQuietly(connection);
                return new Session(unavailable(table,"The selected table was absent from the starting clone."));
            }
            connection.setAutoCommit(false);
            return new Session(connection,table);
        } catch (SQLException | RuntimeException e) {
            if (connection!=null) closeQuietly(connection);
            return new Session(unavailable(table,"Could not open a lock observer on the starting clone."));
        }
    }

    private static boolean exists(Connection connection,String table) throws SQLException {
        try (var query=connection.prepareStatement("""
            select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
            where n.nspname='public' and c.relname=? and c.relkind in ('r','p')
            """)) {
            query.setQueryTimeout(1);
            query.setString(1,table);
            try (var rows=query.executeQuery()) { return rows.next(); }
        }
    }

    static final class Session implements AutoCloseable {
        private final String table;
        private final Connection connection;
        private final AtomicBoolean stop=new AtomicBoolean();
        private final FutureTask<Evidence> task;
        private final Thread sampler;
        private Evidence result;

        private Session(Connection connection,String table) {
            this.table=table;
            this.connection=connection;
            task=new FutureTask<>(() -> observe(connection,table,stop));
            sampler=Thread.ofPlatform().daemon().name("rehearsal-lock-observer").start(task);
        }

        private Session(Evidence result) {
            table=result.table();
            connection=null;
            task=null;
            sampler=null;
            this.result=result;
        }

        synchronized Evidence finish() {
            if (result!=null) return result;
            stop.set(true);
            LockSupport.unpark(sampler);
            try {
                result=task.get(2,TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                cancel();
                Thread.currentThread().interrupt();
                result=unavailable(table,"Lock observation was interrupted.");
            } catch (ExecutionException | TimeoutException e) {
                cancel();
                result=unavailable(table,"Lock observation could not finish within its cleanup deadline.");
            }
            return result;
        }

        private void cancel() {
            abortQuietly(connection);
            task.cancel(true);
            try { sampler.join(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override public void close() { finish(); }
    }

    private static Evidence observe(Connection connection,String table,AtomicBoolean stop) {
        String lock="LOCK TABLE ONLY public.\"" + table + "\" IN ROW EXCLUSIVE MODE";
        var samples=new Samples();
        long started=System.nanoTime();
        try (connection; var command=connection.createStatement()) {
            command.setQueryTimeout(1);
            while (!stop.get() && samples.attempts<MAX_ATTEMPTS && System.nanoTime()-started<SAMPLE_BUDGET) {
                samples.attempts++;
                if (blocked(connection,command,lock)) {
                    samples.blocked++;
                    retry(connection,command,lock,samples);
                }
                if (!stop.get()) LockSupport.parkNanos(SAMPLE_DELAY);
            }
        } catch (SQLException | RuntimeException e) {
            abortQuietly(connection);
            return samples.evidence(table,"UNAVAILABLE","The lock observer could not continue sampling the selected table.");
        }
        if (samples.attempts==0) return unavailable(table,"Flyway finished before a lock sample could be taken.");
        String note=stop.get() ? "Sampled write-intent table locks on the selected relation."
            : "Sampling reached its 30-second or 600-attempt limit.";
        return samples.evidence(table,"MEASURED",note);
    }

    private static boolean blocked(Connection connection,Statement command,String lock) throws SQLException {
        try {
            command.execute(lock + " NOWAIT");
            return false;
        } catch (SQLException e) {
            if (LOCK_UNAVAILABLE.equals(e.getSQLState())) return true;
            throw e;
        } finally {
            connection.rollback();
        }
    }

    private static void retry(Connection connection,Statement command,String lock,Samples samples) throws SQLException {
        try {
            command.execute("SET LOCAL statement_timeout = '1000ms'");
            command.execute("SET LOCAL lock_timeout = '500ms'");
            long started=System.nanoTime();
            try {
                command.execute(lock);
            } catch (SQLException e) {
                if (!LOCK_UNAVAILABLE.equals(e.getSQLState())) throw e;
                samples.timedOut++;
            } finally {
                samples.maxWait=Math.max(samples.maxWait,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            }
        } finally {
            connection.rollback();
        }
    }

    private static Evidence unavailable(String table,String note) {
        return new Evidence(table,"UNAVAILABLE",0,0,0,0,note);
    }

    private static void closeQuietly(Connection connection) {
        try { connection.close(); }
        catch (SQLException | RuntimeException e) { abortQuietly(connection); }
    }

    private static void abortQuietly(Connection connection) {
        try { connection.abort(Runnable::run); }
        catch (SQLException | RuntimeException ignored) {
            // The observer connection also has a short network timeout and closes in its own task.
        }
    }

    private static final class Samples {
        private int attempts;
        private int blocked;
        private int timedOut;
        private long maxWait;

        private Evidence evidence(String table,String status,String note) {
            return new Evidence(table,status,attempts,blocked,timedOut,maxWait,note);
        }
    }
}
