package com.fci.tableapp.service;

/** The file could not be imported (bad value, wrong mapping, database rejected rows, …). */
public class ImportException extends RuntimeException {
    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
