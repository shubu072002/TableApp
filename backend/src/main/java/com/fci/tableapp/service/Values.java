package com.fci.tableapp.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Base64;

/** Converts a column value to text in a way that works for every column type. */
final class Values {

    private Values() {
    }

    static boolean isBinary(int sqlType) {
        return sqlType == Types.BLOB || sqlType == Types.BINARY
                || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY;
    }

    /** Text form of column {@code i}; binary data becomes Base64. Returns null for SQL NULL. */
    static String asText(ResultSet rs, int i, int sqlType) throws SQLException {
        if (isBinary(sqlType)) {
            byte[] bytes = rs.getBytes(i);
            return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
        }
        return rs.getString(i); // also reads CLOB / TEXT columns
    }
}
