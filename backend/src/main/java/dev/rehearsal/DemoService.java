package dev.rehearsal;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
class DemoService {
    private final ProjectService projects;
    private final DatabaseService databases;
    private final WorkspaceStore store;

    DemoService(ProjectService projects,DatabaseService databases,WorkspaceStore store) {
        this.projects=projects;
        this.databases=databases;
        this.store=store;
    }

    @Transactional(rollbackFor=IOException.class)
    ProjectService.View create(String email) throws IOException {
        var project=projects.save(null,email,new ProjectService.Input("Commerce demo",
            "Five independent PostgreSQL migrations against a representative commerce fixture.","18"));
        databases.save(project.id(),email,databases.fixture());
        store.addProbe(project.id(),email,new WorkspaceStore.ProbeInput("Customer directory","OLD",
            "SELECT id, email, display_name FROM users ORDER BY id"));
        store.addProbe(project.id(),email,new WorkspaceStore.ProbeInput("Order totals","OLD",
            "SELECT id, total_cents FROM orders ORDER BY id"));
        for (String name:List.of("01_safe.sql","02_breaking.sql","03_not_null.sql","04_invalid_cast.sql","05_drop_orders.sql")) {
            try (var input=new ClassPathResource("demo/" + name).getInputStream()) {
                store.addMigration(project.id(),email,name,new String(input.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        return project;
    }
}