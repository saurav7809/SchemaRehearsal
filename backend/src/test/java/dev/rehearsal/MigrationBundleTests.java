package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MigrationBundleTests {
    @Test void acceptsVersionedAndRepeatableFilesInAnyUploadOrder() {
        assertDoesNotThrow(() -> WorkspaceStore.validateBundleNames(files(
            "R__Refresh_view.sql","V10__backfill.sql","V2.1__create_table.sql")));
    }

    @ParameterizedTest
    @CsvSource({
        "V1__a.sql,V01.0__b.sql,Duplicate Flyway version",
        "V1_2__a.sql,V1.2__b.sql,Duplicate Flyway version",
        "R__Refresh_view.sql,R__refresh view.sql,Duplicate Flyway repeatable description",
        "V1__a.sql,V1__a.sql,Duplicate filename",
        "V0__a.sql,V1__b.sql,above 0",
        "plain.sql,V1__b.sql,require Flyway filenames"
    })
    void rejectsAmbiguousOrUnrunnableBundles(String first,String second,String evidence) {
        var error=assertThrows(ResponseStatusException.class,
            () -> WorkspaceStore.validateBundleNames(files(first,second)));
        assertEquals(400,error.getStatusCode().value());
        assertTrue(error.getReason().contains(evidence));
    }

    @Test void pdfIncludesTheFailedFileAndPostgresEvidence() throws IOException {
        var report=Map.<String,Object>of("migrationFiles",List.of(
            new Sandbox.MigrationFileResult("V1__first.sql","APPLIED",0L,null),
            new Sandbox.MigrationFileResult("V2__second.sql","FAILED",3L,"SQLSTATE 42701 - duplicate column"),
            new Sandbox.MigrationFileResult("V3__later.sql","PENDING",null,null)));
        var run=new WorkspaceStore.Run(1,1,1,"Bundle","FAILED",45,Instant.EPOCH,3,report);
        try (var document=Loader.loadPDF(new ReportPdf(new ObjectMapper()).render(run))) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("V1__first.sql | APPLIED | 0 ms"));
            assertTrue(text.contains("V2__second.sql | FAILED | 3 ms"));
            assertTrue(text.contains("SQLSTATE 42701"));
            assertTrue(text.contains("V3__later.sql | PENDING"));
        }
    }

    private List<WorkspaceStore.MigrationFile> files(String... names) {
        return java.util.Arrays.stream(names).map(name -> new WorkspaceStore.MigrationFile(name,"DELETE FROM users;")).toList();
    }
}
