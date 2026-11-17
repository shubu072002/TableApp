package com.fci.tableapp.model;

import java.util.List;

/**
 * What the uploaded file contains, returned before anything is written to the database.
 *
 * @param uploadId           pass back to the import call
 * @param sampleRows         first rows as display text
 * @param suggestedTableName valid table name derived from the file name
 */
public record ImportPreview(
        String uploadId,
        String fileName,
        long totalRows,
        List<Column> columns,
        List<List<String>> sampleRows,
        String suggestedTableName) {

    /**
     * @param index         position in the file (0-based)
     * @param name          header text as in the file
     * @param suggestedName valid column name for a new table on this database
     * @param inferredType  TEXT / INTEGER / DECIMAL / DATE / TIMESTAMP / BOOLEAN
     */
    public record Column(int index, String name, String suggestedName, String inferredType, int maxLength) {
    }
}
