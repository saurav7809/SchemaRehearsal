package dev.rehearsal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.SQLException;

@Component("execution")
class ExecutionPolicy implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(ExecutionPolicy.class);
    private final boolean production;
    private final boolean enabled;
    private final JdbcTemplate metadata;
    private final Sandbox sandbox;

    ExecutionPolicy(Environment environment, @Value("${rehearsal.execution-enabled}") boolean enabled,
                    JdbcTemplate metadata, Sandbox sandbox) {
        this.production = environment.matchesProfiles("production");
        this.enabled = enabled;
        this.metadata = metadata;
        this.sandbox = sandbox;
    }

    void requireEnabled() {
        if (!enabled)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Migration execution is disabled by the deployment operator.");
    }

    @Bean
    ApplicationRunner verifyExecutionBoundary() {
        return args -> {
            if (production && enabled) {
                try (var broker = sandbox.broker(); var baseline = sandbox.baseline()) {
                    verify(broker);
                    verifyFixture(baseline);
                }
            }
        };
    }

    void verify(Connection broker) throws SQLException {
        if (!production) return;
        try (var statement = broker.createStatement(); var result = statement.executeQuery("""
            select system_identifier::text,
                current_database()='sr_control' and current_user='sr_broker'
                and current_setting('server_version_num')::int between 180000 and 189999
                and not exists (select 1 from pg_roles where rolname='sr_app')
                and (select count(*) from pg_roles
                    where rolname in ('sr_broker','sr_reader','sr_worker') and rolcanlogin
                      and not (rolsuper or rolcreaterole or rolreplication or rolbypassrls)
                      and rolcreatedb=(rolname='sr_broker')
                      and rolconnlimit between 1 and 4)=3
                and not exists (
                    select 1 from pg_auth_members m join pg_roles r on r.oid=m.member
                    where r.rolname in ('sr_broker','sr_reader','sr_worker'))
                and (select count(*) from pg_database
                    where datname in ('sr_control','sr_fixture')
                      and pg_get_userbyid(datdba)='sr_broker')=2
                and not exists (select 1 from pg_database
                    where datname not in ('postgres','template0','template1','sr_control','sr_fixture')
                      and (datname !~ '^sr_run_[a-f0-9]{32}$' or pg_get_userbyid(datdba)<>'sr_broker'))
                and not has_database_privilege('sr_worker','sr_control','CONNECT')
                and not has_database_privilege('sr_worker','sr_fixture','CONNECT')
            from pg_catalog.pg_control_system()
            """)) {
            if (!result.next() || !result.getBoolean(2))
                throw new SQLException("The rehearsal cluster failed its dedicated-cluster and role checks.");
            String sandboxId = result.getString(1);
            String metadataId = metadata.queryForObject("""
                select system_identifier::text from pg_catalog.pg_control_system()
                where current_database()='sr_metadata' and current_user='sr_app'
                """, String.class);
            if (sandboxId == null || metadataId == null || sandboxId.equals(metadataId))
                throw new SQLException("Rehearsal and metadata require separate PostgreSQL clusters.");
        }
    }

    void verifyFixture(Connection baseline) throws SQLException {
        if (!production) return;
        try (var statement = baseline.createStatement(); var result = statement.executeQuery("""
            select current_database()='sr_fixture' and current_user='sr_reader'
                and not exists (select 1 from pg_extension where extname <> 'plpgsql')
                and not exists (
                    select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                    where p.prosecdef and n.nspname not in ('pg_catalog','information_schema'))
            """)) {
            if (!result.next() || !result.getBoolean(1))
                throw new SQLException("The rehearsal fixture has unsupported extensions or elevated functions.");
        }
    }

    @Override public Health health() {
        if (!production || !enabled) return Health.up().build();
        try (var broker = sandbox.broker()) {
            verify(broker);
            return Health.up().build();
        } catch (SQLException | org.springframework.dao.DataAccessException e) {
            log.debug("Execution readiness check failed ({})", e.getClass().getSimpleName());
            return Health.down().build();
        }
    }
}
