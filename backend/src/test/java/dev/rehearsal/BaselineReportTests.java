package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineReportTests {
    @Test void pdfIdentifiesBothBaselineVersionsAndSourceRuns() throws IOException {
        var report=Map.<String,Object>of(
            "baseline",Map.of("id",42,"name","Before rollout","sourceRunId",7),
            "savedBaseline",Map.of("id",43,"name","After rollout","sourceRunId",8));
        var run=new WorkspaceStore.Run(8,1,1,"Rollout","SUCCEEDED",0,Instant.EPOCH,20,report);

        try (var document=Loader.loadPDF(new ReportPdf(new ObjectMapper()).render(run))) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("Starting baseline: v42 | Before rollout | Source run #7"));
            assertTrue(text.contains("Saved baseline: v43 | After rollout | Source run #8"));
        }
    }
}
