package com.fci.tableapp.service;

import com.fci.tableapp.model.ColumnInfo;
import com.fci.tableapp.model.DbType;
import com.fci.tableapp.model.TableDetails;
import com.fci.tableapp.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.*;
import java.util.*;

/**
 * Reads tables through plain JDBC metadata, so the same code works on PostgreSQL,
 * Oracle and MySQL. Every method works on the user's own {@link DbSession}.
 * <p>
 * Table names coming from the browser are never put into SQL directly: they are first
 * matched against the list of real tables and then quoted as identifiers.
 */
@Service
public class TableService {

    private static final Logger log = LoggerFactory.getLogger(TableService.class);

    private final int fetchSize;

    public TableService(@Value("${app.export.fetch-size:2000}") int fetchSize) {
        this.fetchSize = fetchSize;
    }

    /** All tables in the session's schema (or the user's default schema), sorted by name. */
    public List<TableInfo> listTables(DbSession s) {
        try (Connection c = s.dataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            Scope scope = scope(s, c);
            List<TableInfo> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(scope.catalog(), scope.schema(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name.startsWith("BIN$")) {
                        continue; // Oracle recycle bin
                    }
                    tables.add(new TableInfo(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), name));
                }
            }
            tables.sort(Comparator.comparing(TableInfo::name, String.CASE_INSENSITIVE_ORDER));
            return tables;
        } catch (SQLException e) {
            throw new DbException("Could not read the table list: " + e.getMessage(), e);
        }
    }

    /** What the session is connected to (shown in the UI header). */
    public Map<String, String> connectionInfo(DbSession s) {
        try (Connection c = s.dataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            Scope scope = scope(s, c);
            Map<String, String> info = new LinkedHashMap<>();
            info.put("type", s.type().name().toLowerCase(Locale.ROOT));
            info.put("product", s.type().label());
            info.put("version", md.getDatabaseProductVersion());
            info.put("database", s.databaseLabel());
            info.put("schema", scope.schema() != null ? scope.schema() : scope.catalog());
            info.put("user", md.getUserName());
            return info;
        } catch (SQLException e) {
            throw new DbException("Could not read connection details: " + e.getMessage(), e);
        }
    }

    /** Looks the table up among the real tables; throws if it does not exist. */
    public TableInfo findTable(DbSession s, String schema, String name) {
        return listTables(s).stream()
                .filter(t -> t.name().equals(name))
                .filter(t -> isBlank(schema) || schema.equals(t.schema()) || schema.equals(t.catalog()))
                .findFirst()
                .orElseThrow(() -> new TableNotFoundException(name));
    }

    public TableDetails details(DbSession s, TableInfo table) {
        try (Connection c = s.dataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            List<ColumnInfo> columns = new ArrayList<>();
            String escape = md.getSearchStringEscape();
            String pattern = isBlank(escape) ? table.name()
                    : table.name().replace(escape, escape + escape)
                                  .replace("_", escape + "_")
                                  .replace("%", escape + "%");
            try (ResultSet rs = md.getColumns(table.catalog(), table.schema(), pattern, "%")) {
                while (rs.next()) {
                    columns.add(new ColumnInfo(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("TYPE_NAME"),
                            rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
            }
            return new TableDetails(table, columns, countRows(c, md, table));
        } catch (SQLException e) {
            throw new DbException("Could not read table details: " + e.getMessage(), e);
        }
    }

    /** Runs SELECT * on the table and streams every row through the given exporter. */
    public void export(DbSession s, TableInfo table, TableExporter exporter, OutputStream out) throws IOException {
        s.begin();
        try (Connection c = s.dataSource().getConnection()) {
            String sql = "SELECT * FROM " + qualifiedName(c.getMetaData(), table);
            boolean autoCommit = c.getAutoCommit();
            // PostgreSQL only streams with a cursor (fetchSize) when auto-commit is off
            c.setAutoCommit(false);
            try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                st.setFetchSize(fetchSize);
                try (ResultSet rs = st.executeQuery(sql)) {
                    exporter.write(rs, table.name(), out);
                }
            } finally {
                c.rollback(); // read-only work, nothing to commit
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Export of {} failed", table.name(), e);
            throw new DbException("Export failed: " + e.getMessage(), e);
        } finally {
            s.end();
        }
    }

    // ------------------------------------------------------------------ helpers

    private long countRows(Connection c, DatabaseMetaData md, TableInfo table) {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(30);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualifiedName(md, table))) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Row count for {} failed: {}", table.name(), e.getMessage());
            return -1; // unknown; the UI shows "?"
        }
    }

    /** "HR"."EMPLOYEES" (Oracle), "public"."tasks" (PostgreSQL), `shop`.`orders` (MySQL). */
    private String qualifiedName(DatabaseMetaData md, TableInfo t) throws SQLException {
        String q = md.getIdentifierQuoteString();
        q = (q == null || q.isBlank()) ? "" : q.trim();
        String prefix = !isBlank(t.schema()) ? t.schema() : t.catalog();
        String name = quote(q, t.name());
        return isBlank(prefix) ? name : quote(q, prefix) + "." + name;
    }

    private static String quote(String q, String identifier) {
        if (q.isEmpty()) {
            return identifier;
        }
        return q + identifier.replace(q, q + q) + q;
    }

    private record Scope(String catalog, String schema) {
    }

    /** Where to look for tables. MySQL calls a database a "catalog" and has no schemas. */
    private Scope scope(DbSession s, Connection c) throws SQLException {
        String wanted = s.schema();
        return switch (s.type()) {
            case MYSQL -> new Scope(isBlank(wanted) ? c.getCatalog() : wanted, null);
            // Oracle stores unquoted names in upper case (hr -> HR)
            case ORACLE -> new Scope(null, isBlank(wanted) ? c.getSchema()
                    : wanted.equals(wanted.toLowerCase(Locale.ROOT)) ? wanted.toUpperCase(Locale.ROOT) : wanted);
            // PostgreSQL stores unquoted names in lower case (PUBLIC -> public)
            case POSTGRES -> new Scope(null, isBlank(wanted) ? c.getSchema()
                    : wanted.equals(wanted.toUpperCase(Locale.ROOT)) ? wanted.toLowerCase(Locale.ROOT) : wanted);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
