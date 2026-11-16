package com.fci.tableapp.service;

public class TableNotFoundException extends RuntimeException {
    public TableNotFoundException(String table) {
        super("Table not found: " + table);
    }
}
