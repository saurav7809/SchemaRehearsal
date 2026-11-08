package dev.rehearsal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api")
class ProjectController {
    private final ProjectService projects;
    private final DatabaseService databases;
    ProjectController(ProjectService projects,DatabaseService databases) {
        this.projects=projects;
        this.databases=databases;
    }

    @GetMapping("/projects") List<ProjectService.View> list(Principal p) { return projects.list(p.getName()); }

    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    ProjectService.View create(Principal p,@Valid @RequestBody ProjectService.Input input) {
        return projects.save(null,p.getName(),input);
    }

    @GetMapping("/projects/{id}") ProjectService.View get(Principal p,@PathVariable long id) {
        return ProjectService.View.of(projects.require(id,p.getName()));
    }

    @PutMapping("/projects/{id}") ProjectService.View update(Principal p,@PathVariable long id,
            @Valid @RequestBody ProjectService.Input input) { return projects.save(id,p.getName(),input); }

    @DeleteMapping("/projects/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal p,@PathVariable long id) { projects.delete(id,p.getName()); }

    @PostMapping("/databases/test-connection") Map<String,Object> test(@Valid @RequestBody DatabaseService.Input input) {
        return databases.test(input);
    }

    @GetMapping("/projects/{id}/database")
    DatabaseService.View configuration(Principal p,@PathVariable long id) {
        return databases.configuration(id,p.getName());
    }

    @PostMapping("/projects/{id}/database") DatabaseService.View configure(Principal p,@PathVariable long id,
            @Valid @RequestBody DatabaseService.Input input) { return databases.save(id,p.getName(),input); }

    @GetMapping("/projects/{id}/schema") Map<String,String> schema(Principal p,@PathVariable long id) {
        return databases.schema(id,p.getName());
    }
}