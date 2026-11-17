package com.fci.tableapp.service;

import com.fci.tableapp.model.ConnectRequest;
import com.fci.tableapp.model.DbType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Opens and keeps track of the database connections users create from the UI.
 * <p>
 * The JDBC URL is always built here from validated parts – the browser never sends a raw
 * URL – so driver options (file access, custom socket factories, …) cannot be injected.
 * Passwords are only held inside the in-memory connection pool, never written anywhere.
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    private static final Pattern DB_NAME = Pattern.compile("^[A-Za-z0-9_$#.-]{1,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, DbSession> sessions = new ConcurrentHashMap<>();
    private final long idleMillis;
    private final int maxSessions;

    public ConnectionRegistry(@Value("${app.session.idle-minutes:30}") long idleMinutes,
                              @Value("${app.session.max:50}") int maxSessions) {
        this.idleMillis = idleMinutes * 60_000;
        this.maxSessions = maxSessions;
    }

    /** Validates the details, opens a pool and tests it. Throws {@link ConnectionFailedException} on failure. */
    public DbSession open(ConnectRequest r) {
        DbType type = DbType.from(r.dbType());
        String host = required(r.host(), "Host").trim();
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Host must be a host name or IP address, e.g. localhost or 192.168.1.10");
        }
        int port = r.port() == null ? type.defaultPort() : r.port();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        String database = required(r.database(), type == DbType.ORACLE ? "Service name / SID" : "Database").trim();
        if (!DB_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Database name contains invalid characters");
        }
        String username = required(r.username(), "Username").trim();
        if (sessions.size() >= maxSessions) {
            throw new IllegalArgumentException("Too many open connections – please try again later");
        }

        boolean oracleSid = "sid".equalsIgnoreCase(r.oracleMode());
        String id = newId();

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("db-" + id.substring(0, 8));
        cfg.setJdbcUrl(buildUrl(type, host, port, database, oracleSid));
        cfg.setUsername(username);
        cfg.setPassword(r.password() == null ? "" : r.password());
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(120_000);
        cfg.setConnectionTimeout(20_000);
        cfg.setInitializationFailTimeout(1); // test the connection right now, fail fast
        if (type == DbType.ORACLE) {
            cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        }

        HikariDataSource ds;
        try {
            ds = new HikariDataSource(cfg);
        } catch (RuntimeException e) {
            log.info("Connection to {} {}:{}/{} failed: {}", type.label(), host, port, database, rootMessage(e));
            throw new ConnectionFailedException(friendly(type, rootMessage(e)), e);
        }

        DbSession session = new DbSession(id, type, ds, r.schema(), database);
        sessions.put(id, session);
        log.info("Opened {} session {} to {}:{}/{} as {}", type.label(), cfg.getPoolName(), host, port, database, username);
        return session;
    }

    public DbSession get(String id) {
        DbSession s = id == null ? null : sessions.get(id);
        if (s == null) {
            throw new SessionNotFoundException();
        }
        s.touch();
        return s;
    }

    public void close(String id) {
        DbSession s = id == null ? null : sessions.remove(id);
        if (s != null) {
            s.close();
            log.info("Closed session {}", id.substring(0, Math.min(8, id.length())));
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void closeIdleSessions() {
        long cutoff = System.currentTimeMillis() - idleMillis;
        sessions.values().stream()
                .filter(s -> s.isIdleSince(cutoff))
                .map(DbSession::id)
                .toList()
                .forEach(this::close);
    }

    @PreDestroy
    void closeAll() {
        sessions.keySet().forEach(this::close);
    }

    // ------------------------------------------------------------------ helpers

    static String buildUrl(DbType type, String host, int port, String database, boolean oracleSid) {
        return switch (type) {
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=10&ApplicationName=TableExport&reWriteBatchedInserts=true";
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useCursorFetch=true&connectTimeout=10000&allowPublicKeyRetrieval=true"
                    + "&rewriteBatchedStatements=true";
            case ORACLE -> oracleSid
                    ? "jdbc:oracle:thin:@" + host + ":" + port + ":" + database
                    : "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }

    private static String friendly(DbType type, String msg) {
        // drop Oracle's trailing help link / blank lines
        String m = msg == null ? "" : msg.replaceAll("\\s*https://docs\\.oracle\\.com\\S*", "").trim();
        String lower = m.toLowerCase();
        if (lower.contains("ora-01017") || lower.contains("password authentication failed")
                || lower.contains("access denied for user")) {
            return "Wrong username or password. (" + m + ")";
        }
        if (lower.contains("ora-12514") || lower.contains("ora-12505")) {
            return "The Oracle listener is running, but this service name / SID does not exist on it. "
                    + "Check the name (e.g. ORCL, XEPDB1) or switch between Service name and SID. (" + m + ")";
        }
        if (lower.contains("ora-01109")) {
            return "This Oracle pluggable database exists but is not open. Ask your DBA to open it. (" + m + ")";
        }
        if ((type == DbType.POSTGRES && lower.contains("does not exist")) || lower.contains("unknown database")) {
            return "That database does not exist on the server. (" + m + ")";
        }
        if (lower.contains("connection refused") || lower.contains("communications link failure")
                || lower.contains("ora-12541") || lower.contains("connect timed out")) {
            return "Cannot reach " + type.label() + " at the given host and port. Is the database running? (" + m + ")";
        }
        if (lower.contains("unknownhost") || lower.contains("unknown host")) {
            return "Unknown host name. (" + m + ")";
        }
        return m;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String newId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
