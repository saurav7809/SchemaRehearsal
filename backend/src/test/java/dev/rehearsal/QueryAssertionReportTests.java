package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAssertionReportTests {
    @Test void pdfIncludesTheAssertionOutcomeAndEvidence() throws IOException {
        var assertion=new Compatibility.AssertionResult("SCALAR_EQUALS",0,"FAIL","The returned integer did not match.");
        var migrated=new Compatibility.Result(true,List.of("count : int8"),1,null,assertion);
        var probe=new Compatibility.Comparison(1,"Missing users","NEW","PASS",null,migrated);
        var report=Map.<String,Object>of("compatibility",List.of(probe));
        var run=new WorkspaceStore.Run(1,1,1,"Verify backfill","SUCCEEDED",26,Instant.EPOCH,20,report);

        try (var document=Loader.loadPDF(new ReportPdf(new ObjectMapper()).render(run))) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("Assertion: SCALAR_EQUALS 0 | FAIL"));
            assertTrue(text.contains("The returned integer did not match."));
        }
    }
}
