package com.fci.tableapp.service;

import com.fci.tableapp.model.ColumnType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Pattern;

/** Watches every value of one file column and guesses the best SQL type. */
final class ColumnStats {

    // no leading zeros: "00123" stays text so the zeros are not lost
    private static final Pattern INTEGER = Pattern.compile("^[-+]?(0|[1-9]\\d{0,17})$");
    private static final Pattern DECIMAL = Pattern.compile("^[-+]?(0|[1-9]\\d{0,29})?\\.\\d{1,20}$");
    private static final Pattern BOOL = Pattern.compile("(?i)^(true|false|yes|no)$");

    private boolean seen;
    private boolean canInt = true, canDec = true, canDate = true, canTs = true, canBool = true;
    private int maxLength;

    void accept(Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) return;
        seen = true;
        maxLength = Math.max(maxLength, Values.text(v).length());

        switch (v) {
            case BigDecimal n -> {
                canDate = canTs = canBool = false;
                BigDecimal z = n.stripTrailingZeros();
                if (z.scale() > 0 || z.precision() - z.scale() > 18) canInt = false;
            }
            case LocalDate d -> canInt = canDec = canBool = false;
            case LocalDateTime dt -> {
                canInt = canDec = canBool = false;
                if (!dt.toLocalTime().equals(LocalTime.MIDNIGHT)) canDate = false;
            }
            case Boolean b -> canInt = canDec = canDate = canTs = false;
            default -> {
                String s = v.toString().trim();
                boolean isInt = INTEGER.matcher(s).matches();
                if (!isInt) canInt = false;
                if (!isInt && !DECIMAL.matcher(s).matches()) canDec = false;
                if (canDate && Temporals.date(s) == null) canDate = false;
                if (canTs && Temporals.dateTime(s) == null) canTs = false;
                if (!BOOL.matcher(s).matches()) canBool = false;
            }
        }
    }

    ColumnType type() {
        if (!seen) return ColumnType.TEXT;
        if (canInt) return ColumnType.INTEGER;
        if (canDec) return ColumnType.DECIMAL;
        if (canDate) return ColumnType.DATE;
        if (canTs) return ColumnType.TIMESTAMP;
        if (canBool) return ColumnType.BOOLEAN;
        return ColumnType.TEXT;
    }

    int maxLength() {
        return maxLength;
    }
}
