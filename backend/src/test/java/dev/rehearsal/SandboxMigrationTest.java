package dev.rehearsal;

import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxMigrationTest {
    @Test
    void sortsVersionsNumericallyBeforeRepeatableDescriptions() {
        var scripts = Sandbox.scripts(List.of(file("R__z.sql"),file("V10__later.sql"),file("V2_3__middle.sql"),
            file("R__a_b.sql"),file("V2_2__earlier.sql"),file("R__a-b.sql"),file("R__a.sql")));
        assertEquals(List.of("V2_2__earlier.sql","V2_3__middle.sql","V10__later.sql",
            "R__a.sql","R__a_b.sql","R__a-b.sql","R__z.sql"),scripts.stream().map(Sandbox.Script::filename).toList());
    }

    @Test
    void retainsCommittedFilesAndReportsFailureBeforePendingFiles() {
        var scripts = Sandbox.scripts(List.of(file("R__last.sql"),file("V2__fails.sql"),file("V1__first.sql")));
        var result = new MigrateResult();
        result.putSuccessfulMigration(info("C:\\run\\V1__first.sql","1"),12);
        result.putFailedMigration(info("/run/V2__fails.sql","2"),7);

        String error = "SQLSTATE 42P01 - relation does not exist";
        var summary = Sandbox.summarize(scripts,result,"V2__fails.sql",error);

        assertEquals(1,summary.applied());
        assertEquals(error,summary.error());
        assertEquals(List.of(new Sandbox.MigrationFileResult("V1__first.sql","APPLIED",12L,null),
            new Sandbox.MigrationFileResult("V2__fails.sql","FAILED",7L,error),
            new Sandbox.MigrationFileResult("R__last.sql","PENDING",null,null)),summary.migrationFiles());
    }

    @Test
    void keepsAnOriginalPlainFilenameInTheReport() {
        var scripts = Sandbox.scripts(List.of(file("01_safe.sql")));
        assertEquals("V1__uploaded.sql",scripts.getFirst().filename());
        var result = new MigrateResult();
        result.putSuccessfulMigration(info("/run/V1__uploaded.sql","1"),4);

        var summary = Sandbox.summarize(scripts,result,null,null);

        assertNull(summary.error());
        assertEquals(List.of(new Sandbox.MigrationFileResult("01_safe.sql","APPLIED",4L,null)),summary.migrationFiles());
    }

    private WorkspaceStore.MigrationFile file(String name) {
        return new WorkspaceStore.MigrationFile(name,"CREATE TABLE demo (id INT);");
    }

    private MigrationInfo info(String path,String version) {
        var info = mock(MigrationInfo.class);
        when(info.getPhysicalLocation()).thenReturn(path);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.getType()).thenReturn(CoreMigrationType.SQL);
        return info;
    }
}
