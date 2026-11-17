package com.fci.tableapp.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/** Converts column values between the database and text, for export and import. */
final class Values {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Values() {
    }

    static boolean isBinary(int sqlType) {
        return sqlType == Types.BLOB || sqlType == Types.BINARY
                || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY;
    }

    // ------------------------------------------------------------------ export

    /** Text form of column {@code i}; binary data becomes Base64. Returns null for SQL NULL. */
    static String asText(ResultSet rs, int i, int sqlType) throws SQLException {
        if (isBinary(sqlType)) {
            byte[] bytes = rs.getBytes(i);
            return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
        }
        return rs.getString(i); // also reads CLOB / TEXT columns
    }

    // ------------------------------------------------------------------ import

    /** Display / string form of a value read from an uploaded file. */
    static String text(Object v) {
        return switch (v) {
            case null -> "";
            case BigDecimal n -> n.toPlainString();
            case LocalDateTime dt -> dt.getNano() == 0 ? dt.format(DATE_TIME) : dt.toString().replace('T', ' ');
            default -> v.toString();
        };
    }

    /**
     * Sets parameter {@code idx} from a file value, converted to the target column's JDBC type.
     * Empty text becomes NULL. Throws {@link IllegalArgumentException} with a readable reason
     * when the value does not fit the column.
     */
    static void bind(PreparedStatement ps, int idx, Object v, int sqlType) throws SQLException {
        if (v == null || (v instanceof String s && s.isBlank())) {
            ps.setNull(idx, sqlType);
            return;
        }
        switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                BigDecimal n = number(v).stripTrailingZeros();
                if (n.scale() > 0) throw new IllegalArgumentException("not a whole number");
                try {
                    ps.setLong(idx, n.longValueExact());
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("number too large");
                }
            }
            case Types.NUMERIC, Types.DECIMAL -> ps.setBigDecimal(idx, number(v));
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> ps.setDouble(idx, number(v).doubleValue());
            case Types.DATE -> ps.setObject(idx, date(v));
            case Types.TIMESTAMP -> ps.setObject(idx, dateTime(v));
            case Types.TIMESTAMP_WITH_TIMEZONE -> ps.setObject(idx, offsetDateTime(v));
            case Types.TIME -> ps.setObject(idx, time(v));
            case Types.BOOLEAN, Types.BIT -> ps.setBoolean(idx, bool(v));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ps.setBytes(idx, bytes(v));
            // PostgreSQL uuid / json / enum: let the server cast the text
            case Types.OTHER -> ps.setObject(idx, text(v), Types.OTHER);
            default -> ps.setString(idx, text(v));
        }
    }

    private static BigDecimal number(Object v) {
        return switch (v) {
            case BigDecimal n -> n;
            case Boolean b -> b ? BigDecimal.ONE : BigDecimal.ZERO;
            default -> {
                try {
                    yield new BigDecimal(text(v).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a number");
                }
            }
        };
    }

    private static LocalDate date(Object v) {
        return switch (v) {
            case LocalDate d -> d;
            case LocalDateTime dt -> dt.toLocalDate();
            default -> {
                LocalDate d = Temporals.date(text(v));
                if (d == null) {
                    LocalDateTime dt = Temporals.dateTime(text(v));
                    if (dt == null) throw new IllegalArgumentException("not a date (use e.g. 2026-05-31 or 31-05-2026)");
                    d = dt.toLocalDate();
                }
                yield d;
            }
        };
    }

    private static LocalDateTime dateTime(Object v) {
        return switch (v) {
            case LocalDateTime dt -> dt;
            case LocalDate d -> d.atStartOfDay();
            default -> {
                LocalDateTime dt = Temporals.dateTime(text(v));
                if (dt == null) throw new IllegalArgumentException("not a date/time (use e.g. 2026-05-31 14:30:00)");
                yield dt;
            }
        };
    }

    private static OffsetDateTime offsetDateTime(Object v) {
        return switch (v) {
            case LocalDateTime dt -> dt.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
            case LocalDate d -> d.atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
            default -> {
                OffsetDateTime odt = Temporals.withOffset(text(v));
                if (odt == null) throw new IllegalArgumentException("not a date/time (use e.g. 2026-05-31 14:30:00+05:30)");
                yield odt;
            }
        };
    }

    private static LocalTime time(Object v) {
        if (v instanceof LocalDateTime dt) return dt.toLocalTime();
        try {
            return LocalTime.parse(text(v).trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a time (use e.g. 14:30:00)");
        }
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof BigDecimal n) return n.signum() != 0;
        return switch (text(v).trim().toLowerCase(Locale.ROOT)) {
            case "true", "t", "yes", "y", "1" -> true;
            case "false", "f", "no", "n", "0" -> false;
            default -> throw new IllegalArgumentException("not true/false");
        };
    }

    private static byte[] bytes(Object v) {
        try {
            return Base64.getDecoder().decode(text(v).trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("binary data must be Base64 encoded");
        }
    }
}
