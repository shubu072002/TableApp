package com.fci.tableapp.service;

/** The connection id is unknown – it expired, was closed, or the server restarted. */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException() {
        super("Your database session has expired. Please connect again.");
    }
}
