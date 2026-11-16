package com.fci.tableapp.service;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** RFC 4180 CSV, UTF-8 with BOM so Excel opens non-English text correctly. */
@Component
public class CsvExporter implements TableExporter {

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv; charset=UTF-8";
    }

    @Override
    public void write(ResultSet rs, String tableName, OutputStream out) throws SQLException, IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        w.write('﻿');

        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        int[] types = new int[cols + 1];
        for (int i = 1; i <= cols; i++) {
            types[i] = md.getColumnType(i);
            if (i > 1) w.write(',');
            w.write(escape(md.getColumnLabel(i)));
        }
        w.write("\r\n");

        while (rs.next()) {
            for (int i = 1; i <= cols; i++) {
                if (i > 1) w.write(',');
                String v = Values.asText(rs, i, types[i]);
                if (v != null) w.write(escape(v));
            }
            w.write("\r\n");
        }
        w.flush(); // the servlet stream is closed by Spring, not here
    }

    private static String escape(String v) {
        boolean needsQuotes = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return needsQuotes ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
