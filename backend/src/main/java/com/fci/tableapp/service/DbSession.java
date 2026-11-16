package com.fci.tableapp.service;

import com.fci.tableapp.model.DbType;
import com.zaxxer.hikari.HikariDataSource;

import java.util.concurrent.atomic.AtomicInteger;

/** One user's open database connection (a small pool), identified by a random id. */
public final class DbSession {

    private final String id;
    private final DbType type;
    private final HikariDataSource dataSource;
    private final String schema;        // as typed by the user, may be blank
    private final String databaseLabel; // database / service name, shown in the UI
    private final AtomicInteger activeRequests = new AtomicInteger();
    private volatile long lastUsed = System.currentTimeMillis();

    DbSession(String id, DbType type, HikariDataSource dataSource, String schema, String databaseLabel) {
        this.id = id;
        this.type = type;
        this.dataSource = dataSource;
        this.schema = schema == null ? "" : schema.trim();
        this.databaseLabel = databaseLabel;
    }

    public String id() {
        return id;
    }

    public DbType type() {
        return type;
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public String schema() {
        return schema;
    }

    public String databaseLabel() {
        return databaseLabel;
    }

    /** Marks the start of a long operation (download) so the session is not closed meanwhile. */
    public void begin() {
        activeRequests.incrementAndGet();
        touch();
    }

    public void end() {
        activeRequests.decrementAndGet();
        touch();
    }

    void touch() {
        lastUsed = System.currentTimeMillis();
    }

    boolean isIdleSince(long cutoff) {
        return activeRequests.get() == 0 && lastUsed < cutoff;
    }

    void close() {
        dataSource.close();
    }
}
