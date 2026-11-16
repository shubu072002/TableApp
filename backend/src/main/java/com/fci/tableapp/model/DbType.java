package com.fci.tableapp.model;

import java.util.Locale;

/** Databases the UI can connect to. */
public enum DbType {
    POSTGRES("PostgreSQL", 5432),
    ORACLE("Oracle", 1521),
    MYSQL("MySQL", 3306);

    private final String label;
    private final int defaultPort;

    DbType(String label, int defaultPort) {
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String label() {
        return label;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public static DbType from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Database type is required");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> POSTGRES;
            case "oracle" -> ORACLE;
            case "mysql" -> MYSQL;
            default -> throw new IllegalArgumentException("Unsupported database type: " + value);
        };
    }
}
