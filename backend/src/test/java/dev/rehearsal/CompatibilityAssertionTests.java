package dev.rehearsal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompatibilityAssertionTests {
    private static final String SQL="SELECT id FROM users";
    private final Connection connection=mock(Connection.class);
    private final Savepoint savepoint=mock(Savepoint.class);
    private final PreparedStatement statement=mock(PreparedStatement.class);
    private final ResultSet rows=mock(ResultSet.class);
    private final ResultSetMetaData metadata=mock(ResultSetMetaData.class);

    @Test void exactZeroMatchesAndDoesNotReadValues() throws SQLException {
        prepare(1,0);

        var result=run("ROW_COUNT_EQUALS",0);

        assertEquals("PASS",result.assertion().status());
        assertEquals(0,result.sampledRows());
        verify(statement).setMaxRows(101);
        verify(rows,never()).getBigDecimal(anyInt());
    }

    @Test void exactOneHundredMatchesTheBoundary() throws SQLException {
        prepare(1,100);

        var result=run("ROW_COUNT_EQUALS",100);

        assertEquals("PASS",result.assertion().status());
        assertEquals(100,result.sampledRows());
        verify(rows,times(101)).next();
    }

    @Test void lookaheadFailsAnAssertionWhenResultsExceedTheSample() throws SQLException {
        prepare(1,110);

        var result=run("ROW_COUNT_EQUALS",100);

        assertTrue(result.success());
        assertEquals(100,result.sampledRows());
        assertEquals("FAIL",result.assertion().status());
        assertEquals("More than 100 rows were returned.",result.assertion().reason());
        verify(statement,times(1)).executeQuery();
        verify(rows,times(101)).next();
    }

    @Test void numericScalarRequiresAnExactIntegerAndDoesNotSerializeTheReturnedValue() throws SQLException {
        prepare(1,1);
        when(metadata.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(rows.getBigDecimal(1)).thenReturn(new BigDecimal("8675309.000"));

        var result=run("SCALAR_EQUALS",3);

        assertTrue(result.success());
        assertEquals("FAIL",result.assertion().status());
        assertFalse(new ObjectMapper().valueToTree(result).toString().contains("8675309"));
        verify(rows,times(1)).getBigDecimal(1);
    }

    @Test void exactIntegerDecimalCanMatchIncludingSigned64BitBoundary() throws SQLException {
        prepare(1,1);
        when(metadata.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(rows.getBigDecimal(1)).thenReturn(new BigDecimal("-9223372036854775808.000"));

        assertEquals("PASS",run("SCALAR_EQUALS",Long.MIN_VALUE).assertion().status());
    }

    @ParameterizedTest @ValueSource(strings={"42.125","9223372036854775808","-9223372036854775809"})
    void rejectsFractionalAndOutOfRangeNumericValues(String value) throws SQLException {
        prepare(1,1);
        when(metadata.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(rows.getBigDecimal(1)).thenReturn(new BigDecimal(value));

        assertEquals("FAIL",run("SCALAR_EQUALS",42).assertion().status());
    }

    @Test void neverCoercesTextToNumeric() throws SQLException {
        prepare(1,1);
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);

        assertEquals("FAIL",run("SCALAR_EQUALS",42).assertion().status());
        verify(rows,never()).getBigDecimal(anyInt());
    }

    @Test void rejectsSqlNull() throws SQLException {
        prepare(1,1);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rows.getBigDecimal(1)).thenReturn(null);

        assertEquals("FAIL",run("SCALAR_EQUALS",0).assertion().status());
    }

    @ParameterizedTest @ValueSource(ints={0,2})
    void rejectsMissingAndExtraRows(int count) throws SQLException {
        prepare(1,count);
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(rows.getBigDecimal(1)).thenReturn(BigDecimal.ONE);

        var result=run("SCALAR_EQUALS",1);

        assertEquals("FAIL",result.assertion().status());
        assertEquals("Expected exactly one row and one column.",result.assertion().reason());
    }

    @Test void rejectsExtraColumns() throws SQLException {
        prepare(2,1);

        var result=run("SCALAR_EQUALS",1);

        assertEquals("FAIL",result.assertion().status());
        verify(rows,never()).getBigDecimal(anyInt());
    }

    @Test void failedQueryFailsAssertionAndPreservesExistingSqlStateEvidence() throws SQLException {
        prepare(1,1);
        when(statement.executeQuery()).thenThrow(new SQLException("relation does not exist","42P01"));

        var result=run("ROW_COUNT_EQUALS",1);

        assertFalse(result.success());
        assertTrue(result.error().contains("42P01"));
        assertEquals("FAIL",result.assertion().status());
        assertFalse(result.assertion().reason().contains("relation does not exist"));
        verify(connection).rollback(savepoint);
        verify(connection).releaseSavepoint(savepoint);
    }

    @Test void legacyExecutionKeepsOriginalLimitAndOmitsAssertionField() throws SQLException {
        prepare(1,0);

        var result=Compatibility.run(connection,SQL);

        assertNull(result.assertion());
        assertFalse(new ObjectMapper().valueToTree(result).has("assertion"));
        verify(statement).setMaxRows(100);
    }

    private void prepare(int columns,int count) throws SQLException {
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.prepareStatement(SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(columns);
        var remaining=new AtomicInteger(count);
        when(rows.next()).thenAnswer(ignored -> remaining.getAndDecrement()>0);
    }

    private Compatibility.Result run(String kind,long expected) throws SQLException {
        return Compatibility.run(connection,SQL,new WorkspaceStore.AssertionInput(kind,expected));
    }
}
