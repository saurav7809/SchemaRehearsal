package dev.rehearsal;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

@Component
class Sandbox {
    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    record MigrationExecution(int applied,List<MigrationFileResult> migrationFiles,String error) {}
    record MigrationFileResult(String name,String status,Long durationMs,String error) {}
    record Script(WorkspaceStore.MigrationFile file,String filename,MigrationVersion version) {
        String orderName() {
            return version == null ? filename.substring(3,filename.length()-4).replace('_',' ') : filename;
        }
    }

    private final DatabaseService db;
    Sandbox(DatabaseService db) { this.db=db; }

    Connection broker() throws SQLException {
        return DriverManager.getConnection(db.url("sr_control",db.port()),"sr_broker",db.brokerPassword());
    }

    Connection baseline() throws SQLException { return db.connect(db.fixture()); }

    String name() { return "sr_run_" + UUID.randomUUID().toString().replace("-",""); }

    void provision(Connection broker,String name) throws SQLException {
        checkName(name);
        try (var command=broker.createStatement()) {
            command.execute("CREATE DATABASE " + name + " TEMPLATE sr_fixture CONNECTION LIMIT 4");
            command.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
            command.execute("GRANT CONNECT ON DATABASE " + name + " TO sr_worker");
        }
    }

    void drop(Connection broker,String name) throws SQLException {
        checkName(name);
        try (var command=broker.createStatement()) {
            command.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    Connection worker(String name) throws SQLException {
        checkName(name);
        return DriverManager.getConnection(db.url(name,db.port()),"sr_worker",db.workerPassword());
    }

    MigrationExecution migrate(String name,WorkspaceStore.Migration migration) throws IOException {
        checkName(name);
        List<Script> scripts = scripts(migration.files());
        Path directory=Files.createTempDirectory("schema-rehearsal-");
        List<Path> temporaryFiles = new ArrayList<>(scripts.size());
        try {
            for (Script script : scripts) {
                Path file = directory.resolve(script.filename());
                temporaryFiles.add(file);
                Files.writeString(file,script.file().sql(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            }
            var flyway = Flyway.configure().dataSource(db.url(name,db.port()),"sr_worker",db.workerPassword())
                .locations("filesystem:" + directory.toAbsolutePath())
                .defaultSchema("public").createSchemas(false).cleanDisabled(true)
                .baselineOnMigrate(true).baselineVersion("0").placeholderReplacement(false)
                .skipDefaultCallbacks(true).group(false)
                .validateMigrationNaming(true).lockRetryCount(2)
                .load();
            try {
                return summarize(scripts,flyway.migrate(),null,null);
            } catch (FlywayMigrateException e) {
                // The error result retains completed scripts and the failed script's timing.
                return summarize(scripts,e.getErrorResult(),e.getMigration().getScript(),evidence(e,directory));
            } catch (FlywayException e) {
                return summarize(scripts,null,null,evidence(e,directory));
            }
        } finally {
            // Register the directory first so delete-on-exit removes files before their parent.
            boolean clean = true;
            for (Path file : temporaryFiles) clean &= deleteTemporaryPath(file);
            clean &= deleteTemporaryPath(directory);
            if (!clean) {
                directory.toFile().deleteOnExit();
                temporaryFiles.forEach(file -> file.toFile().deleteOnExit());
                log.warn("A local Flyway workspace could not be removed immediately");
            }
        }
    }

    static List<Script> scripts(List<WorkspaceStore.MigrationFile> files) {
        if (files.isEmpty()) throw new IllegalArgumentException("No SQL files to rehearse");
        List<Script> scripts = new ArrayList<>(files.size());
        for (var file : files) {
            String filename = file.name();
            var version = MigrationNames.version(filename);
            if (version == null && !MigrationNames.isRepeatable(filename)) {
                if (files.size() != 1) throw new IllegalArgumentException("Bundles require Flyway migration filenames");
                filename = "V1__uploaded.sql";
                version = MigrationVersion.fromVersion("1");
            }
            if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9_. -]{0,154}\\.sql"))
                throw new IllegalArgumentException("Invalid Flyway migration filename");
            scripts.add(new Script(file,filename,version));
        }
        scripts.sort(Comparator.comparing(Script::version,Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Script::orderName).thenComparing(Script::filename));
        return scripts;
    }

    static MigrationExecution summarize(List<Script> scripts,MigrateResult result,String failedScript,String error) {
        Map<String,MigrateOutput> applied = new HashMap<>();
        Map<String,MigrateOutput> failed = new HashMap<>();
        if (result != null) {
            for (var output : result.getSuccessfulMigrations()) {
                if (!Boolean.TRUE.equals(output.rolledBack)) applied.put(basename(output.filepath),output);
            }
            for (var output : result.getFailedMigrations()) failed.put(basename(output.filepath),output);
        }
        List<MigrationFileResult> files = new ArrayList<>(scripts.size());
        int count = 0;
        for (Script script : scripts) {
            String filename = script.filename();
            String displayName = script.file().name();
            var failure = failed.get(filename);
            var success = applied.get(filename);
            if (failure != null || filename.equals(failedScript)) {
                files.add(new MigrationFileResult(displayName,"FAILED",failure == null ? null : (long) failure.executionTime,error));
            } else if (success != null) {
                files.add(new MigrationFileResult(displayName,"APPLIED",(long) success.executionTime,null));
                count++;
            } else {
                files.add(new MigrationFileResult(displayName,"PENDING",null,null));
            }
        }
        if (error == null && count != scripts.size())
            error = "Flyway did not apply every migration. Check pending scripts and ensure versions are above baseline version 0.";
        return new MigrationExecution(count,List.copyOf(files),error);
    }

    private static String basename(String path) {
        int separator = Math.max(path.lastIndexOf('/'),path.lastIndexOf('\\'));
        return path.substring(separator + 1);
    }

    private static String evidence(FlywayException error,Path directory) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return Compatibility.error(sql);
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Flyway could not complete the migration.";
        String location = directory.toAbsolutePath().toString();
        String safe = message.replace(location,"<migration workspace>")
            .replace(location.replace('\\','/'),"<migration workspace>");
        return safe.substring(0,Math.min(1800,safe.length()));
    }

    private static boolean deleteTemporaryPath(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void checkName(String name) {
        if (!name.matches("sr_run_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid sandbox identifier");
    }
}
