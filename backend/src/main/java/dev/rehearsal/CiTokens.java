package dev.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

@Service
class CiTokens {
    record Access(long projectId,long baselineId,String ownerEmail) {}

    record View(boolean configured,Long baselineId,String baselineName,Instant createdAt,
                @JsonInclude(JsonInclude.Include.NON_NULL) String token) {
        @Override public String toString() {
            return "CI token settings[configured=" + configured + ", baselineId=" + baselineId + "]";
        }
    }

    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) +([A-Za-z0-9_-]{43})");

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final BaselineStore baselines;
    private final SecureRandom random = new SecureRandom();

    CiTokens(JdbcTemplate jdbc,ProjectService projects,BaselineStore baselines) {
        this.jdbc=jdbc;
        this.projects=projects;
        this.baselines=baselines;
    }

    View settings(long projectId,String email) {
        projects.require(projectId,email);
        return jdbc.query("""
            select t.baseline_id,b.name,t.created_at from project_ci_tokens t
            join project_baselines b on b.id=t.baseline_id and b.project_id=t.project_id and b.published
            where t.project_id=?
            """,(row,n) -> new View(true,row.getLong("baseline_id"),row.getString("name"),
                row.getTimestamp("created_at").toInstant(),null),projectId)
            .stream().findFirst().orElseGet(() -> new View(false,null,null,null,null));
    }

    @Transactional
    View issue(long projectId,long baselineId,String email) {
        long account=lockOwnedProject(projectId,email);
        var baseline=baselines.require(projectId,baselineId).view();
        byte[] secret=new byte[32];
        random.nextBytes(secret);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant createdAt=jdbc.queryForObject("""
            insert into project_ci_tokens(project_id,baseline_id,token_hash) values (?,?,?)
            on conflict (project_id) do update set baseline_id=excluded.baseline_id,
                token_hash=excluded.token_hash,created_at=excluded.created_at returning created_at
            """,(row,n) -> row.getTimestamp("created_at").toInstant(),projectId,baselineId,digest(token));
        projects.audit(account,"CI_TOKEN_ISSUED",projectId);
        return new View(true,baselineId,baseline.name(),createdAt,token);
    }

    @Transactional
    void revoke(long projectId,String email) {
        long account=lockOwnedProject(projectId,email);
        if (jdbc.update("delete from project_ci_tokens where project_id=?",projectId)>0)
            projects.audit(account,"CI_TOKEN_REVOKED",projectId);
    }

    Access authorize(String authorizationHeader) {
        if (authorizationHeader==null) throw unauthorized();
        var match=BEARER.matcher(authorizationHeader);
        if (!match.matches()) throw unauthorized();
        return jdbc.query("""
            select t.project_id,t.baseline_id,a.email from project_ci_tokens t
            join projects p on p.id=t.project_id
            join accounts a on a.id=p.owner_id
            join project_baselines b on b.id=t.baseline_id and b.project_id=t.project_id and b.published
            where t.token_hash=?
            """,(row,n) -> new Access(row.getLong("project_id"),row.getLong("baseline_id"),row.getString("email")),
                digest(match.group(1))).stream().findFirst().orElseThrow(CiTokens::unauthorized);
    }

    private long lockOwnedProject(long projectId,String email) {
        long account=projects.account(email);
        var rows=jdbc.queryForList("select id from projects where id=? and owner_id=? for update",Long.class,projectId,account);
        if (rows.isEmpty()) throw ProjectService.missing();
        return account;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable",e);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"A valid CI bearer token is required.");
    }
}
