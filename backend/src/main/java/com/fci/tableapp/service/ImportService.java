package com.fci.tableapp.service;

import com.fci.tableapp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * Loads an uploaded CSV / Excel file into a table: add rows, replace all rows, or create a new table.
 * Everything runs in one transaction – if any row fails, nothing is kept.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int SAMPLE_ROWS = 20;
    private static final int MAX_NAME = 60;

    private final TableService tableService;
    private final int batchSize;

    public ImportService(TableService tableService, @Value("${app.import.batch-size:1000}") int batchSize) {
        this.tableService = tableService;
        this.batchSize = batchSize;
    }

    // ------------------------------------------------------------------ preview

    /** Reads the whole file once: header, row count, sample rows and a type guess per column. */
    public ImportPreview preview(DbSession s, UploadRegistry.Upload upload) {
        try (TabularFile.RowReader reader = TabularFile.open(upload.path(), upload.fileName())) {
            List<String> header = reader.header();
            if (header.isEmpty()) {
                throw new ImportException("The first row must contain column names.");
            }
            List<ColumnStats> stats = new ArrayList<>();
            header.forEach(h -> stats.add(new ColumnStats()));
            List<List<String>> sample = new ArrayList<>();
            long rows = 0;

            for (List<Object> row; (row = reader.next()) != null; rows++) {
                for (int c = 0; c < header.size(); c++) {
                    stats.get(c).accept(c < row.size() ? row.get(c) : null);
                }
                if (sample.size() < SAMPLE_ROWS) {
                    List<String> display = new ArrayList<>();
                    for (int c = 0; c < header.size(); c++) {
                        display.add(c < row.size() ? Values.text(row.get(c)) : "");
                    }
                    sample.add(display);
                }
            }
            upload.stats(stats);

            List<ImportPreview.Column> columns = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int c = 0; c < header.size(); c++) {
                String suggested = unique(identifier(s.type(), header.get(c)), used);
                columns.add(new ImportPreview.Column(c, header.get(c), suggested,
                        stats.get(c).type().name(), stats.get(c).maxLength()));
            }
            return new ImportPreview(upload.id(), upload.fileName(), rows, columns, sample,
                    identifier(s.type(), tableNameFromFile(upload.fileName())));
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImportException ie) throw ie;
            throw new ImportException("Could not read the file: " + rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------ import

    public ImportResult importData(DbSession s, UploadRegistry.Upload upload, ImportRequest r) {
        String mode = r.mode() == null ? "" : r.mode().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("append", "replace", "create").contains(mode)) {
            throw new IllegalArgumentException("Mode must be append, replace or create");
        }
        long started = System.currentTimeMillis();
        s.begin();
        try (Connection c = s.dataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();

            TableInfo table;
            int[] sources;
            String[] targets;
            boolean created = false;

            if (mode.equals("create")) {
                List<ImportRequest.NewColumn> cols = Optional.ofNullable(r.columns()).orElse(List.of());
                if (cols.isEmpty()) throw new IllegalArgumentException("Choose at least one column for the new table");
                table = createTable(s, c, md, r.table(), cols, upload);
                created = true;
                sources = cols.stream().mapToInt(ImportRequest.NewColumn::source).toArray();
                targets = columnNames(c, md, table); // as stored by the database
            } else {
                table = tableService.findTable(s, r.schema(), r.table());
                List<ImportRequest.Mapping> maps = Optional.ofNullable(r.mappings()).orElse(List.of())
                        .stream().filter(m -> m.target() != null && !m.target().isBlank()).toList();
                if (maps.isEmpty()) throw new IllegalArgumentException("Match at least one file column to a table column");
                Set<String> existing = new HashSet<>(Arrays.asList(columnNames(c, md, table)));
                Set<String> seen = new HashSet<>();
                for (ImportRequest.Mapping m : maps) {
                    if (!existing.contains(m.target())) {
                        throw new IllegalArgumentException("Table " + table.name() + " has no column " + m.target());
                    }
                    if (!seen.add(m.target())) {
                        throw new IllegalArgumentException("Column " + m.target() + " is selected more than once");
                    }
                }
                sources = maps.stream().mapToInt(ImportRequest.Mapping::source).toArray();
                targets = maps.stream().map(ImportRequest.Mapping::target).toArray(String[]::new);
            }

            try {
                long[] counts = load(s, c, md, table, upload, sources, targets, mode.equals("replace"));
                long ms = System.currentTimeMillis() - started;
                log.info("Imported {} rows from {} into {} ({} ms)", counts[0], upload.fileName(), table.name(), ms);
                return new ImportResult(table, counts[0], counts[1], created, ms);
            } catch (RuntimeException e) {
                if (created) dropQuietly(c, md, table);
                throw e;
            }
        } catch (SQLException e) {
            throw new ImportException("Import failed: " + rootMessage(e), e);
        } finally {
            s.end();
        }
    }

    /** Deletes (replace mode) and inserts in one transaction. Returns {inserted, deleted}. */
    private long[] load(DbSession s, Connection c, DatabaseMetaData md, TableInfo table, UploadRegistry.Upload upload,
                        int[] sources, String[] targets, boolean replace) throws SQLException {
        String qualified = TableService.qualifiedName(md, table);
        int[] types = targetTypes(c, qualified, targets);

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(qualified).append(" (");
        for (int i = 0; i < targets.length; i++) {
            if (i > 0) sql.append(", ");
            sql.append(TableService.quote(md, targets[i]));
        }
        sql.append(") VALUES (").append("?, ".repeat(targets.length - 1)).append("?)");

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        long inserted = 0;
        long deleted = 0;
        long dataRow = 0;
        long batchStart = 1;
        try (TabularFile.RowReader reader = TabularFile.open(upload.path(), upload.fileName());
             PreparedStatement ps = c.prepareStatement(sql.toString())) {

            int width = reader.header().size();
            for (int src : sources) {
                if (src < 0 || src >= width) throw new IllegalArgumentException("Unknown file column #" + (src + 1));
            }
            if (replace) {
                try (Statement st = c.createStatement()) {
                    deleted = st.executeUpdate("DELETE FROM " + qualified);
                }
            }

            int inBatch = 0;
            for (List<Object> row; (row = reader.next()) != null; ) {
                dataRow++;
                for (int i = 0; i < sources.length; i++) {
                    Object v = sources[i] < row.size() ? row.get(sources[i]) : null;
                    try {
                        Values.bind(ps, i + 1, v, types[i]);
                    } catch (IllegalArgumentException e) {
                        throw new ImportException("Row " + dataRow + ", column " + targets[i] + ": value '"
                                + shorten(Values.text(v)) + "' is " + e.getMessage() + ". Nothing was imported.");
                    }
                }
                ps.addBatch();
                if (++inBatch == batchSize) {
                    inserted += executeBatch(ps, batchStart, dataRow);
                    inBatch = 0;
                    batchStart = dataRow + 1;
                }
            }
            if (inBatch > 0) {
                inserted += executeBatch(ps, batchStart, dataRow);
            }
            c.commit();
            return new long[]{inserted, deleted};
        } catch (IOException e) {
            rollbackQuietly(c);
            throw new ImportException("Could not read the file: " + e.getMessage(), e);
        } catch (RuntimeException | SQLException e) {
            rollbackQuietly(c);
            if (e instanceof SQLException se) {
                throw new ImportException("The database rejected the data: " + rootMessage(se) + ". Nothing was imported.", se);
            }
            throw (RuntimeException) e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    private static long executeBatch(PreparedStatement ps, long from, long to) {
        try {
            int[] r = ps.executeBatch();
            long n = 0;
            for (int x : r) n += x >= 0 ? x : 1; // SUCCESS_NO_INFO (-2) still means 1 row
            return n;
        } catch (SQLException e) {
            throw new ImportException("The database rejected a row between rows " + from + " and " + to + ": "
                    + rootMessage(e) + ". Nothing was imported.", e);
        }
    }

    // ------------------------------------------------------------------ create table

    private TableInfo createTable(DbSession s, Connection c, DatabaseMetaData md, String requestedName,
                                  List<ImportRequest.NewColumn> cols, UploadRegistry.Upload upload) throws SQLException {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("Enter a name for the new table");
        }
        String name = identifier(s.type(), requestedName);
        boolean exists = tableService.listTables(s).stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
        if (exists) {
            throw new IllegalArgumentException("A table named " + name + " already exists – choose another name "
                    + "or use \"Add rows to an existing table\"");
        }

        TableService.Scope scope = tableService.scope(s, c);
        TableInfo table = new TableInfo(scope.catalog(), scope.schema(), name);
        List<ColumnStats> stats = upload.stats();

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(TableService.qualifiedName(md, table)).append(" (");
        Set<String> used = new HashSet<>();
        for (int i = 0; i < cols.size(); i++) {
            ImportRequest.NewColumn col = cols.get(i);
            String colName = identifier(s.type(), col.name() == null || col.name().isBlank() ? "column_" + (i + 1) : col.name());
            if (!used.add(colName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Column name " + colName + " is used twice");
            }
            int maxLen = col.source() >= 0 && col.source() < stats.size() ? stats.get(col.source()).maxLength() : 4000;
            if (i > 0) ddl.append(", ");
            ddl.append(TableService.quote(md, colName)).append(' ')
               .append(ColumnType.from(col.type()).sql(s.type(), maxLen));
        }
        ddl.append(")");

        try (Statement st = c.createStatement()) {
            st.execute(ddl.toString());
        } catch (SQLException e) {
            throw new ImportException("Could not create table " + name + ": " + rootMessage(e), e);
        }
        if (!c.getAutoCommit()) c.commit();
        log.info("Created table {}", name);
        // read it back so catalog / schema / name are exactly as the database reports them
        TableInfo finalTable = table;
        return tableService.listTables(s).stream()
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(finalTable);
    }

    private static void dropQuietly(Connection c, DatabaseMetaData md, TableInfo table) {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + TableService.qualifiedName(md, table));
            if (!c.getAutoCommit()) c.commit();
            log.info("Dropped table {} after failed import", table.name());
        } catch (SQLException e) {
            log.warn("Could not drop {} after failed import: {}", table.name(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String[] columnNames(Connection c, DatabaseMetaData md, TableInfo t) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + TableService.qualifiedName(md, t) + " WHERE 1 = 0")) {
            ResultSetMetaData m = rs.getMetaData();
            String[] names = new String[m.getColumnCount()];
            for (int i = 0; i < names.length; i++) names[i] = m.getColumnName(i + 1);
            return names;
        }
    }

    private static int[] targetTypes(Connection c, String qualified, String[] targets) throws SQLException {
        Map<String, Integer> byName = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + qualified + " WHERE 1 = 0")) {
            ResultSetMetaData m = rs.getMetaData();
            for (int i = 1; i <= m.getColumnCount(); i++) byName.put(m.getColumnName(i), m.getColumnType(i));
        }
        int[] types = new int[targets.length];
        for (int i = 0; i < targets.length; i++) types[i] = byName.getOrDefault(targets[i], Types.VARCHAR);
        return types;
    }

    /**
     * A safe, unquoted-style name for this database: letters, digits and _, starting with a letter,
     * upper case on Oracle and lower case on PostgreSQL / MySQL (so it is easy to query later).
     */
    static String identifier(DbType db, String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        n = n.replaceAll("^_+|_+$", "");
        if (n.isEmpty()) n = "col";
        if (!Character.isLetter(n.charAt(0))) n = (db == DbType.ORACLE ? "C_" : "c_") + n;
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME);
        return db == DbType.ORACLE ? n.toUpperCase(Locale.ROOT) : n.toLowerCase(Locale.ROOT);
    }

    private static String unique(String name, Set<String> used) {
        String u = name;
        for (int i = 2; !used.add(u.toLowerCase(Locale.ROOT)); i++) u = name + "_" + i;
        return u;
    }

    /** "tasks_20261117_003831.csv" (one of our exports) -> "tasks" */
    private static String tableNameFromFile(String fileName) {
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return base.replaceAll("_\\d{8}_\\d{6}$", "");
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // connection already broken
        }
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        if (t instanceof SQLException se && se.getNextException() != null) t = se.getNextException();
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return m.replaceAll("\\s*https://docs\\.oracle\\.com\\S*", "").trim();
    }
}
