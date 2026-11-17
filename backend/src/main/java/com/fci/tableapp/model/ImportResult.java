package com.fci.tableapp.model;

/**
 * @param rowsInserted rows written from the file
 * @param rowsDeleted  rows removed first (replace mode), otherwise 0
 * @param created      true when a new table was created
 */
public record ImportResult(TableInfo table, long rowsInserted, long rowsDeleted, boolean created, long millis) {
}
