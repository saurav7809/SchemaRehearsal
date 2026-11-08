package dev.rehearsal;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EngineTests {
    @Test void riskExplainsUncappedScoreAndDeduplicatesIdenticalEvidence() {
        var failed=Risk.observed("Failed","CRITICAL","23502","Backfill");
        var regression=Risk.observed("Regression","HIGH","42703","Keep column");
        var result=Risk.score(List.of(failed,failed,regression,
            Risk.observed("Dropped","CRITICAL","Missing table","Stage removal")));
        assertEquals(100,result.score());
        assertEquals(116,result.uncapped());
        assertEquals("CRITICAL",result.classification());
        assertEquals(3,result.factors().size());
        assertEquals("LOW",Risk.score(List.of()).classification());
    }

    @Test void schemaDiffPreservesAddedRemovedAndModifiedDefinitions() {
        var changes=Schema.diff(Map.of("column a","text","index b","unique"),
            Map.of("column a","integer","column c","text"));
        assertEquals(List.of(
            new Schema.Change("column a","MODIFIED","text","integer"),
            new Schema.Change("column c","ADDED",null,"text"),
            new Schema.Change("index b","REMOVED","unique",null)),changes);
    }

    @Test void failedQueryCapturesSqlStateAndRollsBackSavepoint() throws SQLException {
        Connection connection=mock(Connection.class);
        Savepoint savepoint=mock(Savepoint.class);
        PreparedStatement statement=mock(PreparedStatement.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.prepareStatement("select email from users")).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("email does not exist","42703"));

        var result=Compatibility.run(connection,"select email from users");

        assertFalse(result.success());
        assertTrue(result.error().contains("42703"));
        verify(statement).setQueryTimeout(4);
        verify(connection).rollback(savepoint);
        verify(connection).releaseSavepoint(savepoint);
    }

    @Test void oldQueryResultStructureIsPartOfTheCompatibilityContract() {
        var oldProbe=new WorkspaceStore.Probe(1,"Directory","OLD","select id from users");
        var baseline=new Compatibility.Result(true,List.of("id : int8"),2,null);
        var migrated=new Compatibility.Result(true,List.of("id : text"),2,null);
        assertEquals("SHAPE_CHANGED",Compatibility.compare(oldProbe,baseline,migrated).status());
        assertEquals("PASS",Compatibility.compare(oldProbe,baseline,baseline).status());
    }
}