package dev.rehearsal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Service
class WorkspaceStore {
    record MigrationFile(String name,String sql) {}
    record Migration(long id,long projectId,String name,String sql,Instant createdAt,List<MigrationFile> files) {}
    record ProbeInput(@NotBlank @Size(max=120) String name,
                      @NotNull @Pattern(regexp="OLD|NEW") String generation,
                      @NotBlank @Size(max=8000) String sql) {}
    record Probe(long id,String name,String generation,String sql) {}
    record Run(long id,long projectId,long migrationId,String migrationName,String status,int score,
               Instant createdAt,long durationMs,Map<String,Object> report) {}
    record QueuedRun(Run run,BaselineStore.Stored baseline,BaselineStore.Stored snapshot) {}
    record CiOrigin(String repository,long pullRequest,String commit) {}

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final BaselineStore baselines;
    WorkspaceStore(JdbcTemplate jdbc, ProjectService projects, ObjectMapper json,BaselineStore baselines) {
        this.jdbc=jdbc;
        this.projects=projects;
        this.json=json;
        this.baselines=baselines;
    }

    static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason); }

    @Transactional
    Migration upload(long projectId,String email,List<MultipartFile> parts) throws IOException {
        var project=projects.require(projectId,email);
        if (parts.isEmpty() || parts.size()>20) throw bad("Upload between 1 and 20 SQL files.");
        if (parts.stream().mapToLong(MultipartFile::getSize).sum()>256*1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Combined SQL size cannot exceed 256 KiB.");
        List<MigrationFile> files=new ArrayList<>();
        for (MultipartFile part:parts) files.add(readFile(part));
        if (files.size()>1) validateBundleNames(files);
        return insertMigration(project,files);
    }

    private static MigrationFile readFile(MultipartFile file) throws IOException {
        String name=file.getOriginalFilename();
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_. -]{0,154}\\.sql") || file.isEmpty())
            throw bad("Each upload must be a non-empty .sql file with a simple filename.");
        String sql;
        try { sql=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getBytes())).toString(); }
        catch (CharacterCodingException e) { throw bad("SQL files must be UTF-8"); }
        sql=sql.replaceFirst("^\uFEFF","");
        if (sql.isBlank() || sql.indexOf(0)>=0) throw bad("The SQL file is empty or contains binary data");
        return new MigrationFile(name,sql);
    }

    static void validateBundleNames(List<MigrationFile> files) {
        Set<String> names=new HashSet<>();
        Set<String> repeatables=new HashSet<>();
        Set<MigrationVersion> versions=new TreeSet<>();
        var baseline=MigrationVersion.fromVersion("0");
        for (var file:files) {
            String name=file.name();
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw bad("Duplicate filename: " + name);
            var version=MigrationNames.version(name);
            if (version!=null) {
                if (version.compareTo(baseline)<=0 || version.toString().length()>50)
                    throw bad("Flyway versions must be above 0 and at most 50 characters: " + name);
                if (!versions.add(version)) throw bad("Duplicate Flyway version: " + name);
            } else if (MigrationNames.isRepeatable(name)) {
                String description=MigrationNames.repeatableDescription(name);
                if (description.isEmpty()) throw bad("Repeatable migrations require a description: " + name);
                if (!repeatables.add(description.toLowerCase(Locale.ROOT)))
                    throw bad("Duplicate Flyway repeatable description: " + name);
            } else {
                throw bad("Bundles require Flyway filenames (V1__description.sql or R__description.sql): " + name);
            }
        }
    }

    @Transactional
    Migration addMigration(long projectId,String email,String name,String sql) {
        return insertMigration(projects.require(projectId,email),List.of(new MigrationFile(name,sql)));
    }

    private Migration insertMigration(Project project,List<MigrationFile> files) {
        var first=files.getFirst();
        boolean bundle=files.size()>1;
        String suffix=" + " + (files.size()-1) + (files.size()==2 ? " file" : " files");
        String name=bundle ? first.name().substring(0,Math.min(first.name().length(),160-suffix.length())) + suffix : first.name();
        String contents=null;
        if (bundle) {
            try { contents=json.writeValueAsString(files); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Could not serialize migration bundle",e);
            }
        }
        var result=jdbc.queryForObject("""
            insert into migrations(project_id,name,sql_text,bundle_files) values (?,?,?,?::jsonb) returning *
            """,this::migrationRow,project.id,name,bundle ? null : first.sql(),contents);
        projects.audit(project.ownerId,"MIGRATION_UPLOADED",result.id());
        return result;
    }

    Migration migration(long id,String email) {
        return jdbc.query("""
            select m.* from migrations m join projects p on p.id=m.project_id where m.id=? and p.owner_id=?
            """,this::migrationRow,id,projects.account(email)).stream().findFirst().orElseThrow(ProjectService::missing);
    }

    List<Migration> migrations(long id,String email) {
        projects.require(id,email);
        return jdbc.query("select * from migrations where project_id=? order by id desc",this::migrationRow,id);
    }

    private Migration migrationRow(ResultSet r,int row) throws SQLException {
        String name=r.getString("name");
        String bundle=r.getString("bundle_files");
        List<MigrationFile> files;
        try {
            files=bundle==null ? List.of(new MigrationFile(name,r.getString("sql_text")))
                : json.readValue(bundle,new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Could not read migration bundle",e);
        }
        return new Migration(r.getLong("id"),r.getLong("project_id"),name,
            files.getFirst().sql(),r.getTimestamp("created_at").toInstant(),files);
    }

    @Transactional
    Probe addProbe(long id,String email,ProbeInput input) {
        var project=projects.require(id,email);
        jdbc.queryForObject("select id from projects where id=? for update",Long.class,id);
        if (jdbc.queryForObject("select count(*) from probes where project_id=?",Integer.class,id)>=12)
            throw bad("A project supports up to 12 compatibility probes");
        var probe=jdbc.queryForObject("""
            insert into probes(project_id,name,generation,sql_text) values (?,?,?,?) returning *
            """,this::probeRow,id,input.name(),input.generation(),input.sql());
        projects.audit(project.ownerId,"PROBE_ADDED",probe.id());
        return probe;
    }

    List<Probe> probes(long id,String email) {
        projects.require(id,email);
        return jdbc.query("select * from probes where project_id=? order by id",this::probeRow,id);
    }

    void deleteProbe(long id,long probe,String email) {
        projects.require(id,email);
        if (jdbc.update("delete from probes where id=? and project_id=?",probe,id)==0) throw ProjectService.missing();
        projects.audit(projects.account(email),"PROBE_DELETED",probe);
    }

    private Probe probeRow(ResultSet r,int row) throws SQLException {
        return new Probe(r.getLong("id"),r.getString("name"),r.getString("generation"),r.getString("sql_text"));
    }

    @Transactional
    QueuedRun createRun(Migration migration,String email,Long baselineId,BaselineStore.NewSnapshot save,CiOrigin origin) {
        long account=projects.account(email);
        // Serialize the account quota check without blocking audit foreign-key checks.
        jdbc.queryForObject("select id from accounts where id=? for no key update",Long.class,account);
        var rows=jdbc.queryForList("select id from projects where id=? and owner_id=? for update",
            Long.class,migration.projectId(),account);
        if (rows.isEmpty()) throw ProjectService.missing();
        var baseline=baselineId==null ? null : baselines.require(migration.projectId(),baselineId);
        if (jdbc.queryForObject("""
            select count(*) from test_runs r join projects p on p.id=r.project_id
            where p.owner_id=? and r.status in ('QUEUED','RUNNING')
            """,Integer.class,account)>=2)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "An account can have up to two active rehearsals.");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("baseline",baseline==null ? Map.of("name","Shared fixture") : baseline.view());
        if (save!=null) report.put("saveBaselineAs",save.name());
        if (origin!=null) report.put("ci",origin);
        var run=jdbc.queryForObject("""
            insert into test_runs(project_id,migration_id,migration_name,status,score,duration_ms,report,ci_submission)
            values (?,?,?,'QUEUED',0,0,?::jsonb,?) returning *
            """,this::runRow,migration.projectId(),migration.id(),migration.name(),writeReport(report),origin!=null);
        var snapshot=save==null ? null : baselines.reserve(migration.projectId(),run.id(),save);
        projects.audit(account,"TEST_QUEUED",run.id());
        return new QueuedRun(run,baseline,snapshot);
    }

    int updateRun(long id,String status,int score,long duration,Map<String,Object> report) {
        return jdbc.update("""
            update test_runs set status=?,score=?,duration_ms=?,report=?::jsonb
            where id=? and status in ('QUEUED','RUNNING')
            """,status,score,duration,writeReport(report),id);
    }

    private String writeReport(Map<String,Object> report) {
        try {
            return json.writeValueAsString(report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize report",e);
        }
    }

    @Transactional
    void finishRun(long id,String status,int score,long duration,Map<String,Object> report,long account,
                   BaselineStore.Stored snapshot) {
        boolean publish=snapshot!=null && status.equals("SUCCEEDED");
        if (publish) report.put("savedBaseline",baselines.publish(snapshot.view().id(),id,account));
        if (updateRun(id,status,score,duration,report)==1) {
            projects.audit(account,"TEST_" + status,id);
        } else if (publish) {
            throw new IllegalStateException("The rehearsal is no longer active.");
        }
    }

    @Transactional
    void failInterruptedRun(long id,long account) {
        if (jdbc.update("""
            update test_runs set status='FAILED',
                report=(report - 'activeStep') || jsonb_build_object('error',
                    'The backend could not complete the rehearsal. Start a new run.')
            where id=? and status in ('QUEUED','RUNNING')
            """,id)==1) projects.audit(account,"TEST_INTERRUPTED",id);
    }

    @Transactional
    void markInterruptedRuns() {
        jdbc.update("""
            with interrupted as (update test_runs set status='FAILED',
                report=(report - 'activeStep') || jsonb_build_object('error',
                    'The backend stopped before the rehearsal could finish. Start a new run.')
            where status in ('QUEUED','RUNNING') returning id,project_id)
            insert into audit_events(account_id,action,subject_id)
            select p.owner_id,'TEST_INTERRUPTED',r.id from interrupted r join projects p on p.id=r.project_id
            """);
    }

    Run run(long id,String email) {
        return jdbc.query("""
            select r.* from test_runs r join projects p on p.id=r.project_id where r.id=? and p.owner_id=?
            """,this::runRow,id,projects.account(email)).stream().findFirst().orElseThrow(ProjectService::missing);
    }

    Run ciRun(long id,CiTokens.Access access) {
        return jdbc.query("""
            select * from test_runs where id=? and project_id=? and ci_submission
                and report #>> '{baseline,id}'=?
            """,this::runRow,id,access.projectId(),Long.toString(access.baselineId()))
            .stream().findFirst().orElseThrow(ProjectService::missing);
    }

    List<Run> runs(Long projectId,String email) {
        if (projectId != null) {
            projects.require(projectId,email);
            return jdbc.query("select * from test_runs where project_id=? order by id desc",this::runRow,projectId);
        }
        return jdbc.query("""
            select r.id,r.project_id,r.migration_id,r.migration_name,r.status,r.score,r.created_at,r.duration_ms,
                   '{}'::jsonb as report
            from test_runs r join projects p on p.id=r.project_id where p.owner_id=? order by r.id desc
            """,this::runRow,projects.account(email));
    }

    private Run runRow(ResultSet r,int row) throws SQLException {
        try {
            Map<String,Object> report=json.readValue(r.getString("report"),new TypeReference<>() {});
            return new Run(r.getLong("id"),r.getLong("project_id"),r.getLong("migration_id"),
                r.getString("migration_name"),r.getString("status"),r.getInt("score"),
                r.getTimestamp("created_at").toInstant(),r.getLong("duration_ms"),report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Could not read report",e);
        }
    }
}
