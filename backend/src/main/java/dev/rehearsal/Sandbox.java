package dev.rehearsal;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

@Component
class Sandbox {
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

    int migrate(String name,WorkspaceStore.Migration migration) throws IOException {
        checkName(name);
        Path directory=Files.createTempDirectory("schema-rehearsal-");
        String uploaded=migration.name();
        String filename=uploaded.matches("V[0-9]+(?:[._][0-9]+)*__.+\\.sql|R__.+\\.sql")
            ? uploaded : "V1__uploaded.sql";
        Path file=directory.resolve(filename);
        try {
            Files.writeString(file,migration.sql(),StandardCharsets.UTF_8);
            return Flyway.configure().dataSource(db.url(name,db.port()),"sr_worker",db.workerPassword())
                .locations("filesystem:" + directory.toAbsolutePath())
                .defaultSchema("public").createSchemas(false).cleanDisabled(true)
                .baselineOnMigrate(true).baselineVersion("0").placeholderReplacement(false)
                .validateMigrationNaming(true).lockRetryCount(2)
                .load().migrate().migrationsExecuted;
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void checkName(String name) {
        if (!name.matches("sr_run_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid sandbox identifier");
    }
}
