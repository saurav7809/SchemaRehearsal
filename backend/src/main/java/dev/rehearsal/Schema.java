package dev.rehearsal;

import java.sql.*;
import java.util.*;

final class Schema {
    private Schema() {}

    static Map<String,String> capture(Connection connection) throws SQLException {
        Map<String,String> objects=new TreeMap<>();
        try (var statement=connection.createStatement();
             var rows=statement.executeQuery("""
                SELECT 'table ' || format('%I.%I',n.nspname,c.relname) AS object_key,
                       c.relkind::text AS definition
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f')
                  AND c.relname <> 'flyway_schema_history'
                UNION ALL
                SELECT 'column ' || format('%I.%I.%I',n.nspname,c.relname,a.attname),
                       format_type(a.atttypid,a.atttypmod) ||
                       CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE ' NULL' END ||
                       coalesce(' DEFAULT ' || pg_get_expr(d.adbin,d.adrelid),'') ||
                       CASE WHEN a.attidentity <> '' THEN ' IDENTITY ' || a.attidentity::text ELSE '' END
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f')
                  AND c.relname <> 'flyway_schema_history' AND a.attnum>0 AND NOT a.attisdropped
                UNION ALL
                SELECT 'constraint ' || format('%I.%I.%I',n.nspname,c.relname,k.conname), pg_get_constraintdef(k.oid)
                FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname <> 'flyway_schema_history'
                UNION ALL
                SELECT 'index ' || format('%I.%I',schemaname,indexname), indexdef
                FROM pg_indexes WHERE schemaname='public' AND tablename <> 'flyway_schema_history'
                """)) {
            while (rows.next()) objects.put(rows.getString(1),rows.getString(2));
        }
        return objects;
    }

    record UncoveredForeignKey(String table,String constraint,String definition) {}

    static List<UncoveredForeignKey> uncoveredForeignKeys(Connection connection) throws SQLException {
        List<UncoveredForeignKey> keys=new ArrayList<>();
        try (var statement=connection.createStatement();
             var rows=statement.executeQuery("""
                select format('%I.%I',n.nspname,c.relname), f.conname, pg_get_constraintdef(f.oid)
                from pg_constraint f join pg_class c on c.oid=f.conrelid
                join pg_namespace n on n.oid=c.relnamespace
                where f.contype='f' and n.nspname='public' and not exists (
                    select 1 from pg_index i
                    join pg_class ix on ix.oid=i.indexrelid join pg_am am on am.oid=ix.relam
                    where i.indrelid=f.conrelid and i.indisvalid and i.indisready
                      and i.indpred is null and am.amname='btree'
                      and i.indnkeyatts>=cardinality(f.conkey)
                      and f.conkey <@ array(
                          select attribute from unnest(i.indkey) with ordinality as key(attribute,position)
                          where position<=cardinality(f.conkey)
                      )
                )
                order by 1,2
                """)) {
            while (rows.next()) keys.add(new UncoveredForeignKey(rows.getString(1),rows.getString(2),rows.getString(3)));
        }
        return keys;
    }

    record Change(String object, String kind, String before, String after) {}

    static List<Change> diff(Map<String,String> before, Map<String,String> after) {
        var keys=new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<Change> changes=new ArrayList<>();
        for (String key:keys) {
            String old=before.get(key);
            String current=after.get(key);
            if (!Objects.equals(old,current))
                changes.add(new Change(key,old == null ? "ADDED" : current == null ? "REMOVED" : "MODIFIED",old,current));
        }
        return changes;
    }
}