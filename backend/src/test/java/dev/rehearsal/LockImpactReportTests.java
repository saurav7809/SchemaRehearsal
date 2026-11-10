package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LockImpactReportTests {
    @Test void pdfContainsTheTableAndObservedWait() throws IOException {
        var evidence=new LockImpact.Evidence("users","MEASURED",10,2,1,502,"One bounded wait timed out.");
        var report=Map.<String,Object>of("lockImpactTable","users","lockImpact",evidence);
        var run=new WorkspaceStore.Run(1,1,1,"Add column","SUCCEEDED",0,Instant.EPOCH,1000,report);

        try (var document=Loader.loadPDF(new ReportPdf(new ObjectMapper()).render(run))) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("Target table: public.users"));
            assertTrue(text.contains("Samples: 10 | Blocked: 2 | Timed out: 1"));
            assertTrue(text.contains("Longest observed wait: 502 ms"));
            assertTrue(text.contains("One bounded wait timed out."));
        }
    }
}
