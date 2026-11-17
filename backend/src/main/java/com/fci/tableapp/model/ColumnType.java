package com.fci.tableapp.model;

import java.util.Locale;

/** Column types offered when a new table is created from an uploaded file. */
public enum ColumnType {
    TEXT, INTEGER, DECIMAL, DATE, TIMESTAMP, BOOLEAN;

    public static ColumnType from(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown column type: " + value);
        }
    }

    /** SQL type for CREATE TABLE. {@code maxLength} is the longest text value seen in the file. */
    public String sql(DbType db, int maxLength) {
        return switch (this) {
            case TEXT -> switch (db) {
                case POSTGRES -> "TEXT";
                case ORACLE -> maxLength <= 4000 ? "VARCHAR2(4000 CHAR)" : "CLOB";
                case MYSQL -> maxLength <= 255 ? "VARCHAR(255)" : maxLength <= 16_000 ? "TEXT" : "LONGTEXT";
            };
            case INTEGER -> db == DbType.ORACLE ? "NUMBER(19)" : "BIGINT";
            case DECIMAL -> switch (db) {
                case POSTGRES -> "NUMERIC";
                case ORACLE -> "NUMBER";
                case MYSQL -> "DECIMAL(38,10)";
            };
            case DATE -> "DATE";
            case TIMESTAMP -> switch (db) {
                case POSTGRES, ORACLE -> "TIMESTAMP";
                case MYSQL -> "DATETIME(6)";
            };
            case BOOLEAN -> switch (db) {
                case POSTGRES -> "BOOLEAN";
                case ORACLE -> "NUMBER(1)";
                case MYSQL -> "TINYINT(1)";
            };
        };
    }
}
