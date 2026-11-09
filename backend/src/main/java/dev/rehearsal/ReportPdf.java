package dev.rehearsal;

import com.fasterxml.jackson.databind.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;

@Component
class ReportPdf {
    private final ObjectMapper json;
    ReportPdf(ObjectMapper json) { this.json=json; }

    byte[] render(WorkspaceStore.Run run) throws IOException {
        JsonNode report=json.valueToTree(run.report());
        List<String> lines=new ArrayList<>(List.of(
            "SCHEMAREHEARSAL / MIGRATION REPORT", "",
            "Run #" + run.id() + " | " + run.migrationName(),
            run.createdAt() + " | " + run.status() + " | " + run.durationMs() + " ms",
            "Risk: " + run.score() + "/100 " + report.path("risk").path("classification").asText(), ""));
        if (report.has("baseline")) lines.add("Starting baseline: " + baselineLabel(report.path("baseline")));
        if (report.has("savedBaseline")) lines.add("Saved baseline: " + baselineLabel(report.path("savedBaseline")));
        if (report.has("ci")) {
            var ci=report.path("ci");
            lines.add("GitHub: " + ci.path("repository").asText() + " PR #" + ci.path("pullRequest").asText());
            lines.add("Commit: " + ci.path("commit").asText());
        }
        if (report.has("error")) lines.add("Execution error: " + report.path("error").asText());
        if (report.has("cleanupError")) lines.add("Cleanup error: " + report.path("cleanupError").asText());
        lines.add("WORKFLOW");
        for (var step:report.path("steps"))
            lines.add(step.path("name").asText() + " | " + step.path("status").asText() + " | " + step.path("durationMs").asText() + " ms");
        lines.add("");
        if (report.has("migrationFiles")) {
            lines.add("FLYWAY FILES");
            for (var file:report.path("migrationFiles")) {
                String duration=file.path("durationMs").isNumber() ? " | " + file.path("durationMs").asText() + " ms" : "";
                lines.add(file.path("name").asText() + " | " + file.path("status").asText() + duration);
                if (file.path("error").isTextual()) lines.add("Error: " + file.path("error").asText());
            }
            lines.add("");
        }
        lines.add("FINDINGS AND RECOMMENDATIONS");
        if (report.path("findings").isEmpty()) lines.add("No findings were recorded.");
        for (var f:report.path("findings")) {
            lines.add(f.path("severity").asText() + " / " + f.path("source").asText() + " / " + f.path("title").asText());
            if (f.path("filename").isTextual()) lines.add("File: " + f.path("filename").asText());
            if (!f.path("table").isNull() && f.has("table")) lines.add("Table: " + f.path("table").asText());
            if (!f.path("column").isNull() && f.has("column")) lines.add("Column: " + f.path("column").asText());
            if (!f.path("statement").isNull() && f.has("statement")) lines.add("SQL: " + f.path("statement").asText());
            lines.add("Evidence: " + f.path("evidence").asText());
            lines.add("Recommendation: " + f.path("recommendation").asText());
            lines.add("");
        }
        lines.add("SCORE FACTORS (sum capped at 100)");
        for (var factor:report.path("risk").path("factors"))
            lines.add("+" + factor.path("points").asText() + " " + factor.path("title").asText());
        lines.add("");
        lines.add("COMPATIBILITY");
        for (var probe:report.path("compatibility")) {
            lines.add(probe.path("name").asText() + " | " + probe.path("generation").asText() + " | " + probe.path("status").asText());
            for (String side:List.of("baseline","migrated")) {
                var result=probe.path(side);
                if (!result.isNull() && !result.isMissingNode())
                    lines.add(side + ": " + (result.path("success").asBoolean()
                        ? result.path("columns") + " (" + result.path("sampledRows").asText() + " sampled rows)"
                        : result.path("error").asText()));
            }
        }
        lines.add("");
        lines.add("SCHEMA CHANGES");
        for (var change:report.path("changes"))
            lines.add(change.path("kind").asText() + " " + change.path("object").asText() + " | "
                + change.path("before").asText("-") + " -> " + change.path("after").asText("-"));
        var wrapped=lines.stream().flatMap(line -> wrap(line).stream()).toList();
        try (var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
            var font=new PDType1Font(Standard14Fonts.FontName.COURIER);
            for (int offset=0;offset<wrapped.size();offset+=52) {
                var page=new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var content=new PDPageContentStream(document,page)) {
                    content.beginText();
                    content.setFont(font,10);
                    content.setLeading(14);
                    content.newLineAtOffset(38,800);
                    for (String line:wrapped.subList(offset,Math.min(offset+52,wrapped.size()))) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private List<String> wrap(String line) {
        String ascii=line.replaceAll("\\s+"," ").replaceAll("[^ -~]","?");
        if (ascii.isEmpty()) return List.of("");
        List<String> result=new ArrayList<>();
        while (ascii.length()>85) {
            int split=ascii.lastIndexOf(' ',85);
            if (split<=0) split=85;
            result.add(ascii.substring(0,split));
            ascii=ascii.substring(split).stripLeading();
        }
        result.add(ascii);
        return result;
    }

    private String baselineLabel(JsonNode baseline) {
        String label=baseline.path("name").asText();
        if (baseline.hasNonNull("id")) label="v" + baseline.path("id").asText() + " | " + label;
        if (baseline.hasNonNull("sourceRunId")) label+=" | Source run #" + baseline.path("sourceRunId").asText();
        return label;
    }
}
