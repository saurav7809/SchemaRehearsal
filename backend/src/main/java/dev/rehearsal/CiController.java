package dev.rehearsal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/ci/rehearsals")
class CiController {
    private final CiTokens tokens;
    private final RunEngine engine;
    private final WorkspaceStore store;
    private final ObjectMapper json;

    CiController(CiTokens tokens,RunEngine engine,WorkspaceStore store,ObjectMapper json) {
        this.tokens=tokens;
        this.engine=engine;
        this.store=store;
        this.json=json;
    }

    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    CiVerdict.View submit(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestParam("file") List<MultipartFile> files,@RequestParam String repository,
            @RequestParam long pullRequest,@RequestParam String commit) throws IOException {
        var access=tokens.authorize(authorization);
        if (!repository.matches("[a-zA-Z0-9_.-]{1,100}/[a-zA-Z0-9_.-]{1,100}")
                || pullRequest<1 || !commit.matches("[a-f0-9]{40}"))
            throw WorkspaceStore.bad("A valid repository, pull request and commit SHA are required.");
        var run=engine.submitCi(access,files,new WorkspaceStore.CiOrigin(repository,pullRequest,commit));
        return CiVerdict.of(run,json.valueToTree(run.report()));
    }

    @GetMapping("/{id}")
    CiVerdict.View status(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @PathVariable long id) {
        var run=store.ciRun(id,tokens.authorize(authorization));
        return CiVerdict.of(run,json.valueToTree(run.report()));
    }
}

final class CiVerdict {
    private static final Pattern SQLSTATE=Pattern.compile("SQLSTATE ([0-9A-Z]{5})\\b");
    private CiVerdict() {}

    record View(long runId,String status,String verdict,String reason,String failedFile,
                String sqlState,int compatibilityRegressions,String reportPath) {}

    static View of(WorkspaceStore.Run run,JsonNode report) {
        String verdict;
        String reason;
        String failedFile=null;
        String evidence=report.path("error").asText();
        int regressions=0;
        if (run.status().equals("FAILED")) {
            for (var file:report.path("migrationFiles")) {
                if (!file.path("status").asText().equals("FAILED")) continue;
                failedFile=file.path("name").asText();
                evidence=file.path("error").asText(evidence);
                break;
            }
            verdict="FAIL";
            reason=failedFile==null ? "Rehearsal failed" : "Migration file failed";
        } else if (run.status().equals("SUCCEEDED")) {
            for (var probe:report.path("compatibility")) {
                String status=probe.path("status").asText();
                if (status.equals("REGRESSION") || status.equals("SHAPE_CHANGED")) {
                    regressions++;
                    if (evidence.isEmpty()) evidence=probe.path("migrated").path("error").asText();
                }
            }
            verdict=regressions==0 ? "PASS" : "FAIL";
            reason=regressions==0 ? "Migration rehearsal passed" : "Existing query compatibility regressed";
        } else {
            verdict="PENDING";
            reason="Rehearsal is in progress";
        }
        var match=SQLSTATE.matcher(evidence);
        return new View(run.id(),run.status(),verdict,reason,failedFile,match.find() ? match.group(1) : null,
            regressions,"/reports/" + run.id());
    }
}
