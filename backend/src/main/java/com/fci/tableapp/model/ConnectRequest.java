package com.fci.tableapp.model;

/**
 * Connection details typed into the UI.
 *
 * @param dbType     postgres | oracle | mysql
 * @param database   database name (PostgreSQL / MySQL) or service name / SID (Oracle)
 * @param oracleMode "service" (default) or "sid" – Oracle only
 * @param schema     optional; blank = the user's default schema
 */
public record ConnectRequest(
        String dbType,
        String host,
        Integer port,
        String database,
        String oracleMode,
        String username,
        String password,
        String schema) {

    /** Never print the password (e.g. in logs). */
    @Override
    public String toString() {
        return "ConnectRequest[" + dbType + " " + username + "@" + host + ":" + port + "/" + database + "]";
    }
}
