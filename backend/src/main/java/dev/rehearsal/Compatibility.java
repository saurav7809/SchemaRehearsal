package dev.rehearsal;

import java.sql.*;
import java.util.*;

final class Compatibility {
    private Compatibility() {}
    record Result(boolean success,List<String> columns,int sampledRows,String error) {}
    record Comparison(long id,String name,String generation,String status,Result baseline,Result migrated) {}

    static Result run(Connection connection,String sql) throws SQLException {
        var savepoint=connection.setSavepoint();
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(4);
            statement.setMaxRows(100);
            try (var rows=statement.executeQuery()) {
                var metadata=rows.getMetaData();
                List<String> columns=new ArrayList<>();
                for (int i=1;i<=metadata.getColumnCount();i++)
                    columns.add(metadata.getColumnLabel(i) + " : " + metadata.getColumnTypeName(i));
                int count=0;
                while (rows.next()) count++;
                return new Result(true,columns,count,null);
            }
        } catch (SQLException e) {
            return new Result(false,List.of(),0,error(e));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
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