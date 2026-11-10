package dev.rehearsal;

import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
class RunEngine {
    record Request(@Positive Long baselineId,@Size(max=80) String saveBaselineAs,
                   @Pattern(regexp="[a-z_][a-z0-9_]{0,62}") String lockImpactTable) {
        Request(Long baselineId,String saveBaselineAs) { this(baselineId,saveBaselineAs,null); }
    }

    private static final Logger log=LoggerFactory.getLogger(RunEngine.class);
    private static final long CLUSTER_LOCK=787713936294L;
    private final WorkspaceStore store;
    private final DatabaseService databases;
    private final Analyzer analyzer;
    private final Sandbox sandbox;
    private final ProjectService projects;
    private final ExecutionPolicy policy;
    private final MetadataLease lease;
    private final BaselineStore baselines;
    private final LockImpact lockImpact;
    private final Semaphore slots=new Semaphore(5);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    RunEngine(WorkspaceStore store,DatabaseService databases,Analyzer analyzer,Sandbox sandbox,
              ProjectService projects,ExecutionPolicy policy,MetadataLease lease,BaselineStore baselines,
              LockImpact lockImpact) {
        this.store=store;
        this.databases=databases;
        this.analyzer=analyzer;
        this.sandbox=sandbox;
        this.projects=projects;
        this.policy=policy;
        this.lease=lease;
        this.baselines=baselines;
        this.lockImpact=lockImpact;
    }

    WorkspaceStore.Run submit(long id,String email,Request request) {
        return submit(() -> store.migration(id,email),email,request,null);
    }

    WorkspaceStore.Run submitCi(CiTokens.Access access,List<MultipartFile> files,WorkspaceStore.CiOrigin origin)
            throws IOException {
        try {
            return submit(() -> {
                try { return store.upload(access.projectId(),access.ownerEmail(),files); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            },access.ownerEmail(),new Request(access.baselineId(),null),origin);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private WorkspaceStore.Run submit(Supplier<WorkspaceStore.Migration> source,String email,Request request,
                                      WorkspaceStore.CiOrigin origin) {
        policy.requireEnabled();
        lease.requireActive();
        if (!slots.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Rehearsal queue is full");
        try {
            var migration=source.get();
            Long baselineId=request==null ? null : request.baselineId();
            String lockImpactTable=request==null ? null : request.lockImpactTable();
            var input=baselineId==null ? databases.load(migration.projectId()) : null;
            if (input!=null) databases.requireFixture(input);
            var save=snapshotRequest(request);
            var validation=analyzer.inspect(migration);
            if (!validation.valid()) throw WorkspaceStore.bad(validation.error());
            var probes=store.probes(migration.projectId(),email);
            long account=projects.account(email);
            var queued=store.createRun(migration,email,baselineId,save,origin,lockImpactTable);
            var run=queued.run();
            try {
                worker.execute(() -> {
                    try { execute(queued,migration,input,probes,validation.findings(),account,lockImpactTable); }
                    catch (RuntimeException e) { recordFailure(run.id(),account,e); }
                    finally { slots.release(); }
                });
            } catch (RejectedExecutionException e) {
                recordFailure(run.id(),account,e);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The rehearsal queue is shutting down");
            }
            return run;
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    private BaselineStore.NewSnapshot snapshotRequest(Request request) {
        if (request==null || request.saveBaselineAs()==null) return null;
        String name=request.saveBaselineAs().strip();
        if (name.isEmpty() || name.chars().anyMatch(Character::isISOControl))
            throw WorkspaceStore.bad("Enter a non-empty baseline name without control characters.");
        return new BaselineStore.NewSnapshot(name,sandbox.baselineName());
    }

    private void execute(WorkspaceStore.QueuedRun queued,WorkspaceStore.Migration migration,DatabaseService.Input input,
                         List<WorkspaceStore.Probe> probes,List<Risk.Finding> warnings,long account,String lockImpactTable) {
        long id=queued.run().id();
        var progress=new Progress(queued.run(),warnings);
        String database=sandbox.name();
        String template=queued.baseline()==null ? "sr_fixture" : queued.baseline().databaseName();
        String status="FAILED";
        try (var broker=sandbox.broker()) {
            lease.requireActive();
            policy.verify(broker);
            lockAndRecover(broker);
            try {
                if (input!=null) {
                    try (var baseline=databases.connect(input)) {
                        policy.verifyFixture(baseline);
                        progress.data.put("registeredSchema",Schema.capture(baseline));
                    }
                }
                progress.advance("Temporary database");
                sandbox.provision(broker,database,template);
                Map<String,String> before;
                List<Schema.UncoveredForeignKey> uncoveredBefore;
                Map<Long,Compatibility.Result> baselineResults=new HashMap<>();
                try (var c=readOnlyWorker(database)) {
                    before=Schema.capture(c);
                    uncoveredBefore=Schema.uncoveredForeignKeys(c);
                    for (var probe:probes)
                        if (probe.generation().equals("OLD"))
                            baselineResults.put(probe.id(),Compatibility.run(c,probe.sql()));
                }
                progress.data.put("before",before);
                progress.advance("Migration execution");
                Sandbox.MigrationExecution execution;
                long migrationMs;
                try (var observation=lockImpactTable==null ? null : lockImpact.start(database,lockImpactTable)) {
                    long started=System.nanoTime();
                    try {
                        execution=sandbox.migrate(database,migration);
                    } finally {
                        migrationMs=millis(started);
                        progress.data.put("migrationDurationMs",migrationMs);
                        if (observation!=null) progress.data.put("lockImpact",observation.finish());
                    }
                }
                progress.data.put("migrationsApplied",execution.applied());
                progress.data.put("migrationFiles",execution.migrationFiles());
                if (execution.error()!=null) throw new IllegalStateException(execution.error());
                if (execution.applied()!=migration.files().size())
                    throw new IllegalStateException("Flyway did not apply every uploaded migration. Versioned files must be above version 0.");
                if (migrationMs>=3000) progress.findings.add(Risk.observed("Slow fixture execution","MEDIUM",
                    "Flyway took " + migrationMs + " ms including connection and history overhead.",
                    "Measure execution and lock duration on representative data volumes."));
                progress.advance("Compatibility testing");
                try (var c=readOnlyWorker(database)) {
                    var after=Schema.capture(c);
                    progress.data.put("after",after);
                    var changes=Schema.diff(before,after);
                    progress.data.put("changes",changes);
                    for (var key:Schema.uncoveredForeignKeys(c)) {
                        if (!uncoveredBefore.contains(key))
                            progress.findings.add(new Risk.Finding("OBSERVED","New foreign key lacks a covering B-tree",
                                "MEDIUM",key.table(),null,null,key.constraint() + ": " + key.definition() +
                                ". The referencing columns are absent from the leading keys of any valid, unfiltered B-tree index.",
                                "Measure parent deletes and key updates on representative data. Consider indexing the referencing columns."));
                    }
                    for (var change:changes) {
                        if (change.kind().equals("REMOVED") && change.object().startsWith("table "))
                            progress.findings.add(Risk.observed("Relation disappeared","HIGH",change.object() + " is absent after migration.",
                                "Review dependent callers and confirm whether this is a removal or a rename."));
                    }
                    List<Compatibility.Comparison> comparisons=new ArrayList<>();
                    for (var probe:probes) {
                        var comparison=Compatibility.compare(probe,baselineResults.get(probe.id()),
                            Compatibility.run(c,probe.sql(),probe.assertion()));
                        comparisons.add(comparison);
                        addCompatibilityFinding(progress.findings,comparison);
                    }
                    progress.data.put("compatibility",comparisons);
                }
                if (queued.snapshot()!=null) {
                    progress.advance("Save baseline snapshot");
                    sandbox.saveBaseline(broker,database,queued.snapshot().databaseName());
                }
                status="SUCCEEDED";
            } finally {
                cleanup(broker,database,progress);
            }
        } catch (Exception e) {
            String error=evidence(e);
            progress.data.put("error",error);
            progress.findings.add(Risk.observed("Rehearsal step failed","CRITICAL",progress.stage + ": " + error,
                "Resolve the recorded error, then rehearse again against the baseline fixture."));
            progress.endStep("FAILED");
            if (e instanceof RuntimeException && !(e instanceof FlywayException) && !(e instanceof IllegalStateException))
                log.warn("Rehearsal {} failed in {} ({})",id,progress.stage,e.getClass().getSimpleName());
        }
        if (lockImpactTable!=null && !progress.data.containsKey("lockImpact"))
            progress.data.put("lockImpact",new LockImpact.Evidence(lockImpactTable,"UNAVAILABLE",0,0,0,0,
                "The rehearsal ended before migration execution could be observed."));
        progress.advance("Risk analysis");
        var score=Risk.score(progress.findings);
        progress.data.put("risk",score);
        progress.advance("Final report");
        progress.endStep("SUCCEEDED");
        if (progress.data.containsKey("cleanupError")) status="FAILED";
        store.finishRun(id,status,score.score(),millis(progress.started),progress.data,account,queued.snapshot());
        log.info("Rehearsal {} completed status={} durationMs={}",id,status,millis(progress.started));
    }

    private void recordFailure(long id,long account,RuntimeException cause) {
        log.error("Rehearsal {} could not finish ({})",id,cause.getClass().getSimpleName());
        try {
            store.failInterruptedRun(id,account);
        } catch (RuntimeException e) {
            log.error("Rehearsal {} failure could not be persisted ({})",id,e.getClass().getSimpleName());
        }
    }

    private Connection readOnlyWorker(String database) throws SQLException {
        var connection=sandbox.worker(database);
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private void lockAndRecover(Connection broker) throws SQLException {
        try (var command=broker.createStatement();
             var result=command.executeQuery("select pg_try_advisory_lock(" + CLUSTER_LOCK + ")")) {
            if (!result.next() || !result.getBoolean(1)) throw new SQLException("Another backend is rehearsing on this cluster");
        }
        List<String> stale=new ArrayList<>();
        try (var command=broker.createStatement();
             var rows=command.executeQuery("""
                select datname from pg_database where datname ~ '^sr_run_[a-f0-9]{32}$'
                and pg_get_userbyid(datdba)=current_user
                """)) {
            while (rows.next()) stale.add(rows.getString(1));
        }
        for (String name:stale) sandbox.drop(broker,name);
        // Reclaim only known allocations from this metadata database, never other snapshots on the cluster.
        for (String name:baselines.reclaimable()) {
            try {
                sandbox.dropBaseline(broker,name);
                baselines.forget(name);
            } catch (SQLException e) {
                log.warn("A removed baseline could not be reclaimed (SQLSTATE {})",e.getSQLState());
            }
        }
    }

    private void cleanup(Connection broker,String database,Progress progress) {
        try { sandbox.drop(broker,database); }
        catch (SQLException e) {
            progress.data.put("cleanupError",Compatibility.error(e));
            progress.findings.add(Risk.observed("Temporary database cleanup failed","HIGH",
                Compatibility.error(e),"Restore broker connectivity. The next run retries stale sandbox cleanup."));
        }
    }

    private static void addCompatibilityFinding(List<Risk.Finding> findings,Compatibility.Comparison c) {
        var assertion=c.migrated().assertion();
        if (c.migrated().success() && assertion!=null && assertion.status().equals("FAIL"))
            findings.add(Risk.observed("Query result assertion failed","HIGH",
                c.name() + ": " + assertion.reason(),
                "Verify the migration preserves the intended data and rerun the assertion."));
        switch (c.status()) {
            case "REGRESSION", "NEW_QUERY_FAILED" -> findings.add(Risk.observed("Compatibility query failed","HIGH",
                c.name() + ": " + c.migrated().error(),"Update the migration or stage a compatibility layer, then rerun this query."));
            case "SHAPE_CHANGED" -> findings.add(Risk.observed("Query result shape changed","HIGH",
                c.name() + ": " + c.baseline().columns() + " → " + c.migrated().columns(),
                "Preserve the original result columns and types until existing consumers are upgraded."));
            case "BASELINE_FAILED" -> findings.add(Risk.observed("Baseline query already fails","LOW",
                c.name() + ": " + c.baseline().error(),"Correct the baseline fixture or probe before judging migration compatibility."));
            default -> { }
        }
    }

    private static String evidence(Exception e) {
        for (Throwable cause=e;cause!=null;cause=cause.getCause())
            if (cause instanceof SQLException sql) return Compatibility.error(sql);
        if (e instanceof IllegalStateException || e instanceof FlywayException) {
            String message=e.getMessage();
            if (message!=null) return message.substring(0,Math.min(message.length(),1800));
        }
        return "The " + e.getClass().getSimpleName() + " interrupted the rehearsal.";
    }

    private static long millis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }

    private final class Progress {
        final long id;
        final long started=System.nanoTime();
        final Map<String,Object> data=new LinkedHashMap<>();
        final List<Map<String,Object>> steps=new ArrayList<>();
        final List<Risk.Finding> findings;
        String stage="Database snapshot";
        long stepStarted=System.nanoTime();
        boolean open=true;

        Progress(WorkspaceStore.Run run,List<Risk.Finding> initial) {
            this.id=run.id();
            findings=new ArrayList<>(initial);
            data.putAll(run.report());
            data.put("steps",steps);
            data.put("findings",findings);
        }

        void advance(String next) {
            endStep("SUCCEEDED");
            stage=next;
            stepStarted=System.nanoTime();
            open=true;
            data.put("activeStep",next);
            store.updateRun(id,"RUNNING",0,millis(started),data);
        }

        void endStep(String status) {
            if (!open) return;
            steps.add(Map.of("name",stage,"status",status,"durationMs",millis(stepStarted)));
            data.remove("activeStep");
            open=false;
        }
    }

    @PreDestroy void stop() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15,TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
