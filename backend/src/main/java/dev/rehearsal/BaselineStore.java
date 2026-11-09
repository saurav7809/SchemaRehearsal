package dev.rehearsal;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Service
class BaselineStore {
    record View(long id,long projectId,String name,Instant createdAt,long sourceRunId) {}
    record Stored(View view,String databaseName) {}
    record NewSnapshot(String name,String databaseName) {}

    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    BaselineStore(JdbcTemplate jdbc,ProjectService projects) {
        this.jdbc=jdbc;
        this.projects=projects;
    }

    List<View> list(long projectId,String email) {
        projects.require(projectId,email);
        return jdbc.query("""
            select * from project_baselines
            where project_id=? and published order by id desc
            """,(row,n) -> read(row).view(),projectId);
    }

    // The caller holds the owning project row lock when reserving or selecting for a run.
    Stored require(long projectId,long id) {
        return jdbc.query("""
            select * from project_baselines where project_id=? and id=? and published
            """,(row,n) -> read(row),projectId,id).stream().findFirst().orElseThrow(ProjectService::missing);
    }

    Stored reserve(long projectId,long runId,NewSnapshot snapshot) {
        int count=jdbc.queryForObject("""
            select count(*) from project_baselines b join test_runs r on r.id=b.source_run_id
            where b.project_id=? and (b.published or r.status in ('QUEUED','RUNNING'))
            """,Integer.class,projectId);
        if (count>=5) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "A project can retain up to five baselines. Remove a version before saving another.");
        return jdbc.queryForObject("""
            insert into project_baselines(project_id,source_run_id,name,database_name)
            values (?,?,?,?) returning *
            """,(row,n) -> read(row),projectId,runId,snapshot.name(),snapshot.databaseName());
    }

    View publish(long id,long sourceRunId,long account) {
        var baseline=jdbc.queryForObject("""
            update project_baselines set published=true,created_at=now()
            where id=? and source_run_id=? and project_id is not null and not published returning *
            """,(row,n) -> read(row).view(),id,sourceRunId);
        projects.audit(account,"BASELINE_SAVED",baseline.id());
        return baseline;
    }

    @Transactional
    void delete(long projectId,long baselineId,String email) {
        var project=projects.require(projectId,email);
        var owned=jdbc.queryForList("select id from projects where id=? and owner_id=? for update",
            Long.class,projectId,project.ownerId);
        if (owned.isEmpty()) throw ProjectService.missing();
        require(projectId,baselineId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from test_runs where project_id=? and status in ('QUEUED','RUNNING'))
            """,Boolean.class,projectId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for this project's rehearsals to finish.");
        jdbc.update("update project_baselines set project_id=null where id=?",baselineId);
        projects.audit(project.ownerId,"BASELINE_REMOVED",baselineId);
    }

    List<String> reclaimable() {
        return jdbc.queryForList("""
            select b.database_name from project_baselines b
            left join test_runs r on r.id=b.source_run_id
            where b.project_id is null or r.id is null
                or (not b.published and r.status in ('SUCCEEDED','FAILED'))
            order by b.id
            """,String.class);
    }

    void forget(String databaseName) {
        jdbc.update("delete from project_baselines where database_name=?",databaseName);
    }

    private Stored read(ResultSet row) throws SQLException {
        return new Stored(new View(row.getLong("id"),row.getLong("project_id"),row.getString("name"),
            row.getTimestamp("created_at").toInstant(),row.getLong("source_run_id")),row.getString("database_name"));
    }
}
