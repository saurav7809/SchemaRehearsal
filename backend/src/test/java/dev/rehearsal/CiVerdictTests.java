package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CiVerdictTests {
    private final ObjectMapper json=new ObjectMapper();

    @Test void failedMigrationReturnsTheFileAndSqlStateWithoutRawErrorEvidence() throws Exception {
        var report=json.readTree("""
            {"error":"SQLSTATE 42701 - secret data",
             "migrationFiles":[{"name":"V1__ok.sql","status":"APPLIED"},
                {"name":"V2__duplicate.sql","status":"FAILED","error":"SQLSTATE 42701 - secret data"}]}
            """);
        var verdict=CiVerdict.of(run("FAILED"),report);
        assertEquals("FAIL",verdict.verdict());
        assertEquals("V2__duplicate.sql",verdict.failedFile());
        assertEquals("42701",verdict.sqlState());
        assertFalse(json.writeValueAsString(verdict).contains("secret data"));
    }

    @Test void blocksAnExistingQueryRegressionEvenWhenFlywaySucceeds() throws Exception {
        var report=json.readTree("""
            {"compatibility":[{"status":"REGRESSION","migrated":{"error":"SQLSTATE 42703 - missing column"}},
                {"status":"SHAPE_CHANGED"},{"status":"NEW_QUERY_FAILED"},{"status":"BASELINE_FAILED"}]}
            """);
        var verdict=CiVerdict.of(run("SUCCEEDED"),report);
        assertEquals("FAIL",verdict.verdict());
        assertEquals(2,verdict.compatibilityRegressions());
        assertEquals("42703",verdict.sqlState());
        assertEquals("/reports/42",verdict.reportPath());
    }

    @Test void passesSuccessfulRehearsalsWithoutAnExistingQueryRegression() {
        assertEquals("PASS",CiVerdict.of(run("SUCCEEDED"),json.createObjectNode()).verdict());
        assertEquals("PENDING",CiVerdict.of(run("QUEUED"),json.createObjectNode()).verdict());
    }

    private WorkspaceStore.Run run(String status) {
        return new WorkspaceStore.Run(42,1,2,"CI rehearsal",status,0,Instant.EPOCH,0,Map.of());
    }
}
