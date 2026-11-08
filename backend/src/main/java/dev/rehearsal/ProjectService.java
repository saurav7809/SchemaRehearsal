package dev.rehearsal;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

@Service
class ProjectService {
    record Input(@NotBlank @Size(max=120) String name, @NotNull @Size(max=2000) String description,
                 @Pattern(regexp="18") @NotNull String postgresVersion) {}
    record View(long id, String name, String description, String postgresVersion, Instant createdAt) {
        static View of(Project p) { return new View(p.id,p.name,p.description,p.postgresVersion,p.createdAt); }
    }
    private final Accounts accounts;
    private final Projects projects;
    private final JdbcTemplate jdbc;
    ProjectService(Accounts accounts, Projects projects, JdbcTemplate jdbc) {
        this.accounts=accounts;
        this.projects=projects;
        this.jdbc=jdbc;
    }

    long account(String email) { return accounts.findByEmail(email).orElseThrow().id; }

    Project require(long id, String email) {
        return projects.findByIdAndOwnerId(id, account(email)).orElseThrow(ProjectService::missing);
    }

    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"); }

    List<View> list(String email) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(account(email)).stream().map(View::of).toList();
    }

    @Transactional
    View save(Long id, String email, Input input) {
        Project p = id == null ? new Project() : require(id,email);
        p.ownerId=account(email);
        p.name=input.name().strip();
        p.description=input.description();
        p.postgresVersion=input.postgresVersion();
        projects.saveAndFlush(p);
        audit(p.ownerId,id == null ? "PROJECT_CREATED" : "PROJECT_UPDATED",p.id);
        return View.of(p);
    }

    @Transactional
    void delete(long id, String email) {
        Project p = require(id,email);
        jdbc.queryForObject("select id from projects where id=? for update",Long.class,id);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from test_runs where project_id=? and status in ('QUEUED','RUNNING'))",
            Boolean.class,id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for this project's rehearsals to finish");
        projects.delete(p);
        audit(p.ownerId,"PROJECT_DELETED",id);
    }

    void audit(long account, String action, long id) {
        jdbc.update("insert into audit_events(account_id,action,subject_id) values (?,?,?)",account,action,id);
    }
}