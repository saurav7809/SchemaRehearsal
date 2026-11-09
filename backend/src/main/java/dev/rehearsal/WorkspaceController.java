package dev.rehearsal;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api")
class WorkspaceController {
    private final WorkspaceStore store;
    private final RunEngine engine;
    private final Analyzer analyzer;
    private final ReportPdf pdf;
    private final DemoService demo;

    WorkspaceController(WorkspaceStore store,RunEngine engine,Analyzer analyzer,ReportPdf pdf,DemoService demo) {
        this.store=store;
        this.engine=engine;
        this.analyzer=analyzer;
        this.pdf=pdf;
        this.demo=demo;
    }

    @PostMapping("/projects/demo") @ResponseStatus(HttpStatus.CREATED)
    ProjectService.View demo(Principal p) throws IOException { return demo.create(p.getName()); }

    @PostMapping("/projects/{id}/migrations/upload") @ResponseStatus(HttpStatus.CREATED)
    WorkspaceStore.Migration upload(Principal p,@PathVariable long id,@RequestParam("file") List<MultipartFile> files) throws IOException {
        return store.upload(id,p.getName(),files);
    }

    @GetMapping("/projects/{id}/migrations")
    List<WorkspaceStore.Migration> migrations(Principal p,@PathVariable long id) { return store.migrations(id,p.getName()); }

    @GetMapping("/migrations/{id}")
    WorkspaceStore.Migration migration(Principal p,@PathVariable long id) { return store.migration(id,p.getName()); }

    @PostMapping("/migrations/{id}/validate")
    Analyzer.Result validate(Principal p,@PathVariable long id) {
        return analyzer.inspect(store.migration(id,p.getName()));
    }

    @PostMapping("/migrations/{id}/test") @ResponseStatus(HttpStatus.ACCEPTED)
    WorkspaceStore.Run test(Principal p,@PathVariable long id) { return engine.submit(id,p.getName()); }

    @GetMapping("/projects/{id}/queries")
    List<WorkspaceStore.Probe> queries(Principal p,@PathVariable long id) { return store.probes(id,p.getName()); }

    @PostMapping("/projects/{id}/queries") @ResponseStatus(HttpStatus.CREATED)
    WorkspaceStore.Probe addQuery(Principal p,@PathVariable long id,@Valid @RequestBody WorkspaceStore.ProbeInput input) {
        var result=analyzer.inspect(input.sql(),true);
        if (!result.valid()) throw WorkspaceStore.bad(result.error());
        return store.addProbe(id,p.getName(),input);
    }

    @DeleteMapping("/projects/{id}/queries/{probe}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteQuery(Principal p,@PathVariable long id,@PathVariable long probe) { store.deleteProbe(id,probe,p.getName()); }

    @GetMapping({"/tests/{id}","/reports/{id}"})
    WorkspaceStore.Run run(Principal p,@PathVariable long id) { return store.run(id,p.getName()); }

    @GetMapping({"/projects/{id}/tests","/projects/{id}/reports"})
    List<WorkspaceStore.Run> runs(Principal p,@PathVariable long id) { return store.runs(id,p.getName()); }

    @GetMapping("/reports")
    List<WorkspaceStore.Run> allRuns(Principal p) { return store.runs(null,p.getName()); }

    @GetMapping("/reports/{id}/download")
    ResponseEntity<byte[]> download(Principal p,@PathVariable long id) throws IOException {
        var run=store.run(id,p.getName());
        if (Set.of("QUEUED","RUNNING").contains(run.status()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"Report is still in progress");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=rehearsal-" + id + ".pdf")
            .body(pdf.render(run));
    }
}
