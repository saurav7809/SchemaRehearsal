package dev.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.sql.*;
import java.util.*;

final class Compatibility {
    private Compatibility() {}
    private static final int MAX_SAMPLE_ROWS=100;

    record AssertionResult(String kind,long expected,String status,String reason) {}
    record Result(boolean success,List<String> columns,int sampledRows,String error,
                  @JsonInclude(JsonInclude.Include.NON_NULL) AssertionResult assertion) {
        Result(boolean success,List<String> columns,int sampledRows,String error) {
            this(success,columns,sampledRows,error,null);
        }
    }
    record Comparison(long id,String name,String generation,String status,Result baseline,Result migrated) {}

    static Result run(Connection connection,String sql) throws SQLException {
        return run(connection,sql,null);
    }

    static Result run(Connection connection,String sql,WorkspaceStore.AssertionInput assertion) throws SQLException {
        boolean countAssertion=assertion!=null && assertion.kind().equals("ROW_COUNT_EQUALS");
        boolean scalarAssertion=assertion!=null && assertion.kind().equals("SCALAR_EQUALS");
        // The extra row distinguishes an exact count of 100 from a larger result.
        int limit=countAssertion ? MAX_SAMPLE_ROWS+1 : MAX_SAMPLE_ROWS;
        var savepoint=connection.setSavepoint();
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(4);
            statement.setMaxRows(limit);
            try (var rows=statement.executeQuery()) {
                var metadata=rows.getMetaData();
                int columnCount=metadata.getColumnCount();
                List<String> columns=new ArrayList<>();
                for (int i=1;i<=columnCount;i++)
                    columns.add(metadata.getColumnLabel(i) + " : " + metadata.getColumnTypeName(i));
                int count=0;
                Long scalar=null;
                while (count<limit && rows.next()) {
                    count++;
                    if (count==1 && scalarAssertion && columnCount==1) scalar=integerScalar(rows,metadata);
                }
                return new Result(true,columns,Math.min(count,MAX_SAMPLE_ROWS),null,
                    evaluate(assertion,columnCount,count,scalar));
            }
        } catch (SQLException e) {
            return new Result(false,List.of(),0,error(e),outcome(assertion,"The query failed before the assertion could be verified."));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static Long integerScalar(ResultSet rows,ResultSetMetaData metadata) {
        try {
            return switch (metadata.getColumnType(1)) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL -> {
                    var value=rows.getBigDecimal(1);
                    yield value==null ? null : value.longValueExact();
                }
                default -> null;
            };
        } catch (ArithmeticException | SQLException ignored) {
            // Conversion details can contain a raw value. Keep that value out of the report.
            return null;
        }
    }

    private static AssertionResult evaluate(WorkspaceStore.AssertionInput assertion,int columns,int count,Long scalar) {
        if (assertion==null) return null;
        String failure=switch (assertion.kind()) {
            case "ROW_COUNT_EQUALS" -> count>MAX_SAMPLE_ROWS ? "More than 100 rows were returned."
                : count==assertion.expected() ? null : "Returned " + count + " rows.";
            case "SCALAR_EQUALS" -> {
                if (columns!=1 || count!=1) yield "Expected exactly one row and one column.";
                if (scalar==null) yield "Expected a non-null, exact numeric integer within the signed 64-bit range.";
                yield scalar.equals(assertion.expected()) ? null : "The returned integer did not match the expected value.";
            }
            default -> "The assertion type is unsupported.";
        };
        return outcome(assertion,failure);
    }

    private static AssertionResult outcome(WorkspaceStore.AssertionInput assertion,String failure) {
        if (assertion==null) return null;
        return new AssertionResult(assertion.kind(),assertion.expected(),failure==null ? "PASS" : "FAIL",
            failure==null ? "The result matched the expected value." : failure);
    }

    static Comparison compare(WorkspaceStore.Probe probe,Result baseline,Result migrated) {
        String status;
        if (probe.generation().equals("NEW")) status=migrated.success() ? "PASS" : "NEW_QUERY_FAILED";
        else if (!baseline.success()) status="BASELINE_FAILED";
        else if (!migrated.success()) status="REGRESSION";
        else status=baseline.columns().equals(migrated.columns()) ? "PASS" : "SHAPE_CHANGED";
        return new Comparison(probe.id(),probe.name(),probe.generation(),status,baseline,migrated);
    }

    static String error(SQLException e) {
        String message=e.getMessage();
        if (message==null) message="Database operation failed";
        return "SQLSTATE " + e.getSQLState() + " - " + message.substring(0,Math.min(1800,message.length()));
    }
}
