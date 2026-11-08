package dev.rehearsal;

import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

@Service
class DatabaseService {
    record Input(@Pattern(regexp="127\\.0\\.0\\.1|localhost") @NotNull String host,
                 @Min(1) @Max(65535) int port,
                 @Pattern(regexp="[a-zA-Z0-9_]{1,63}") @NotNull String databaseName,
                 @Pattern(regexp="[a-zA-Z0-9_]{1,63}") @NotNull String username,
                 @NotBlank @Size(max=512) String password) {}
    record View(String host, int port, String databaseName, String username) {}
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final int port;
    private final String brokerPassword;
    private final String workerPassword;
    private final String readerPassword;
    private final boolean production;

    DatabaseService(JdbcTemplate jdbc, ProjectService projects, Environment environment,
            @Value("${SR_ENCRYPTION_KEY}") String encodedKey,
            @Value("${SR_PG_PORT:5432}") int port,
            @Value("${SR_BROKER_PASSWORD:}") String brokerPassword,
            @Value("${SR_WORKER_PASSWORD:}") String workerPassword,
            @Value("${SR_READER_PASSWORD:}") String readerPassword) {
        this.jdbc=jdbc;
        this.projects=projects;
        byte[] bytes=Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("SR_ENCRYPTION_KEY must contain 32 Base64-encoded bytes");
        key=new SecretKeySpec(bytes,"AES");
        this.port=port;
        this.brokerPassword=brokerPassword;
        this.workerPassword=workerPassword;
        this.readerPassword=readerPassword;
        production=environment.matchesProfiles("production");
    }

    int port() { return port; }
    String brokerPassword() { return brokerPassword; }
    String workerPassword() { return workerPassword; }

    String url(String database, int port) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database
            + "?connectTimeout=4&socketTimeout=20&options=-c%20statement_timeout=15000%20-c%20lock_timeout=3000";
    }

    Connection connect(Input input) throws SQLException {
        if (production) requireFixture(input);
        Connection connection=DriverManager.getConnection(url(input.databaseName(),input.port()),input.username(),input.password());
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    Map<String,Object> test(Input input) {
        try (var connection=connect(input); var statement=connection.createStatement();
             var row=statement.executeQuery("show server_version")) {
            row.next();
            return Map.of("connected",true,"version",row.getString(1));
        } catch (SQLException e) { throw unavailable(e); }
    }

    @Transactional
    View save(long id,String email,Input input) {
        var project=projects.require(id,email);
        test(input);
        jdbc.update("""
            insert into database_configs values (?,?,?,?,?,?)
            on conflict (project_id) do update set host=excluded.host, port=excluded.port,
                database_name=excluded.database_name, username=excluded.username,
                encrypted_password=excluded.encrypted_password
            """,id,input.host(),input.port(),input.databaseName(),input.username(),encrypt(input.password()));
        projects.audit(project.ownerId,"DATABASE_CONFIGURED",id);
        return new View(input.host(),input.port(),input.databaseName(),input.username());
    }

    View configuration(long id,String email) {
        projects.require(id,email);
        return jdbc.query("select host,port,database_name,username from database_configs where project_id=?",
            (r,n) -> new View(r.getString("host"),r.getInt("port"),r.getString("database_name"),r.getString("username")),
            id).stream().findFirst().orElse(new View("127.0.0.1",port,"sr_fixture","sr_reader"));
    }

    Input load(long id) {
        return jdbc.query("select * from database_configs where project_id=?", (r,n) -> new Input(
            r.getString("host"),r.getInt("port"),r.getString("database_name"),r.getString("username"),
            decrypt(r.getString("encrypted_password"))),id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Configure a baseline database first"));
    }

    Map<String,String> schema(long id,String email) {
        projects.require(id,email);
        try (var connection=connect(load(id))) { return Schema.capture(connection); }
        catch (SQLException e) { throw unavailable(e); }
    }

    Input fixture() { return new Input("127.0.0.1",port,"sr_fixture","sr_reader",readerPassword); }

    void requireFixture(Input input) {
        if (input.port()!=port || !Set.of("127.0.0.1","localhost").contains(input.host())
                || !input.databaseName().equals("sr_fixture") || !input.username().equals("sr_reader"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Rehearsals require the controlled sr_fixture baseline");
    }

    private String encrypt(String password) {
        byte[] nonce=new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext=crypt(Cipher.ENCRYPT_MODE,nonce,password.getBytes(StandardCharsets.UTF_8));
        byte[] stored=Arrays.copyOf(nonce,nonce.length+ciphertext.length);
        System.arraycopy(ciphertext,0,stored,nonce.length,ciphertext.length);
        return Base64.getEncoder().encodeToString(stored);
    }

    private String decrypt(String value) {
        byte[] data=Base64.getDecoder().decode(value);
        return new String(crypt(Cipher.DECRYPT_MODE,Arrays.copyOf(data,12),Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode,byte[] nonce,byte[] payload) {
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,key,new GCMParameterSpec(128,nonce));
            return cipher.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Database credential encryption failed",e);
        }
    }

    private static ResponseStatusException unavailable(SQLException e) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Database operation failed (SQLSTATE " + e.getSQLState() + ")");
    }
}
