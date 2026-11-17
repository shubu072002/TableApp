package com.fci.tableapp.model;

import java.util.List;

/**
 * How to load an uploaded file.
 *
 * @param mode     append (add rows) | replace (delete existing rows first) | create (new table)
 * @param schema   schema of the target table (append / replace); ignored for create
 * @param table    existing table (append / replace) or name of the new table (create)
 * @param mappings append / replace: which file column goes into which table column
 * @param columns  create: the columns of the new table
 */
public record ImportRequest(
        String mode,
        String schema,
        String table,
        List<Mapping> mappings,
        List<NewColumn> columns) {

    /** File column {@code source} (0-based) is written to table column {@code target}. */
    public record Mapping(int source, String target) {
    }

    /** New table column {@code name} of {@code type}, filled from file column {@code source}. */
    public record NewColumn(int source, String name, String type) {
    }
}
