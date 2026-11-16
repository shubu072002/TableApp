package com.fci.tableapp.model;

/**
 * A PostgreSQL table: {@code catalog} is the database name (e.g. taskdb),
 * {@code schema} the schema (e.g. public).
 */
public record TableInfo(String catalog, String schema, String name) {
}
