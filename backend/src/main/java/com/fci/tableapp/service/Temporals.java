package com.fci.tableapp.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;

/**
 * Parses date / time text found in uploaded files.
 * Accepted dates: 2026-05-10, 2026/05/10, 10-05-2026, 10/05/2026 (day first).
 * A time may follow after a space or 'T': 14:30, 14:30:15, 14:30:15.123456, with optional offset (+05:30, Z).
 */
final class Temporals {

    private Temporals() {
    }

    private static final List<String> DATE_PATTERNS = List.of("uuuu-MM-dd", "uuuu/MM/dd", "dd-MM-uuuu", "dd/MM/uuuu");

    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalEnd()
            .toFormatter();

    private static final List<DateTimeFormatter> DATES = DATE_PATTERNS.stream()
            .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT))
            .toList();

    private static final List<DateTimeFormatter> DATE_TIMES = DATE_PATTERNS.stream()
            .map(p -> new DateTimeFormatterBuilder().appendPattern(p).appendLiteral(' ').append(TIME)
                    .toFormatter().withResolverStyle(ResolverStyle.STRICT))
            .toList();

    private static final List<DateTimeFormatter> OFFSET_DATE_TIMES = DATE_PATTERNS.stream()
            .map(p -> new DateTimeFormatterBuilder().appendPattern(p).appendLiteral(' ').append(TIME)
                    .appendOffset("+HH:mm", "Z")
                    .toFormatter().withResolverStyle(ResolverStyle.STRICT))
            .toList();

    /** "2026-05-10T14:30" -> "2026-05-10 14:30" */
    private static String normalize(String s) {
        String t = s.trim();
        int tPos = t.indexOf('T');
        if (tPos == 10 && t.length() > 11) {
            t = t.substring(0, 10) + ' ' + t.substring(11);
        }
        return t;
    }

    /** A date, or a date-time at exactly midnight. Null if not a date. */
    static LocalDate date(String s) {
        String t = normalize(s);
        LocalDate d = plainDate(t);
        if (d != null) return d;
        LocalDateTime dt = localDateTime(t);
        return dt != null && dt.toLocalTime().equals(LocalTime.MIDNIGHT) ? dt.toLocalDate() : null;
    }

    /** A date-time (an offset, if present, is dropped – wall-clock time kept). Null if not a date/time. */
    static LocalDateTime dateTime(String s) {
        String t = normalize(s);
        LocalDateTime dt = localDateTime(t);
        if (dt != null) return dt;
        OffsetDateTime odt = offsetDateTime(t);
        if (odt != null) return odt.toLocalDateTime();
        LocalDate d = plainDate(t);
        return d == null ? null : d.atStartOfDay();
    }

    /** A date-time with offset; without an offset the server's time zone is assumed. */
    static OffsetDateTime withOffset(String s) {
        String t = normalize(s);
        OffsetDateTime odt = offsetDateTime(t);
        if (odt != null) return odt;
        LocalDateTime dt = dateTime(t);
        return dt == null ? null : dt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static LocalDate plainDate(String t) {
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private static LocalDateTime localDateTime(String t) {
        for (DateTimeFormatter f : DATE_TIMES) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private static OffsetDateTime offsetDateTime(String t) {
        for (DateTimeFormatter f : OFFSET_DATE_TIMES) {
            try {
                return OffsetDateTime.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return null;
    }
}
