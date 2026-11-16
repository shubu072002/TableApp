package com.fci.tableapp.service;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.*;

/**
 * .xlsx export using POI's streaming workbook, which keeps only a window of rows in
 * memory. Tables above Excel's 1,048,576-row sheet limit continue on extra sheets.
 */
@Component
public class ExcelExporter implements TableExporter {

    private static final int MAX_ROWS = SpreadsheetVersion.EXCEL2007.getMaxRows();
    private static final int MAX_CELL_TEXT = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
    /** Excel keeps 15 significant digits; longer numbers (e.g. IDs) are written as text. */
    private static final int MAX_NUMERIC_PRECISION = 15;

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public void write(ResultSet rs, String tableName, OutputStream out) throws SQLException, IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            wb.setCompressTempFiles(true);
            Styles styles = new Styles(wb);

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            String[] headers = new String[cols];
            int[] types = new int[cols + 1];
            for (int i = 1; i <= cols; i++) {
                headers[i - 1] = md.getColumnLabel(i);
                types[i] = md.getColumnType(i);
            }

            int sheetNo = 1;
            Sheet sheet = newSheet(wb, tableName, sheetNo, headers, styles);
            int rowNum = 1;

            while (rs.next()) {
                if (rowNum >= MAX_ROWS) {
                    sheet = newSheet(wb, tableName, ++sheetNo, headers, styles);
                    rowNum = 1;
                }
                Row row = sheet.createRow(rowNum++);
                for (int i = 1; i <= cols; i++) {
                    writeCell(rs, i, types[i], row.createCell(i - 1), styles);
                }
            }
            wb.write(out);
        }
    }

    private static Sheet newSheet(Workbook wb, String table, int no, String[] headers, Styles styles) {
        String base = no == 1 ? table : table + " (" + no + ")";
        String name = WorkbookUtil.createSafeSheetName(base.length() > 31 ? base.substring(0, 31) : base);
        Sheet sheet = wb.createSheet(name);
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(c, Math.min(60, Math.max(12, headers[c].length() + 4)) * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void writeCell(ResultSet rs, int i, int type, Cell cell, Styles styles) throws SQLException {
        switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> {
                BigDecimal n = rs.getBigDecimal(i);
                if (n == null) return;
                if (n.precision() <= MAX_NUMERIC_PRECISION) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    cell.setCellValue(n.toPlainString());
                }
            }
            case Types.DATE -> {
                Date d = rs.getDate(i);
                if (d == null) return;
                cell.setCellValue(d.toLocalDate());
                cell.setCellStyle(styles.date);
            }
            case Types.TIMESTAMP -> {
                Timestamp ts = rs.getTimestamp(i);
                if (ts == null) return;
                cell.setCellValue(ts.toLocalDateTime());
                cell.setCellStyle(styles.dateTime);
            }
            case Types.BOOLEAN -> {
                boolean b = rs.getBoolean(i);
                if (!rs.wasNull()) cell.setCellValue(b);
            }
            default -> {
                String s = Values.asText(rs, i, type);
                if (s == null) return;
                cell.setCellValue(s.length() > MAX_CELL_TEXT ? s.substring(0, MAX_CELL_TEXT) : s);
            }
        }
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle date;
        final CellStyle dateTime;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CreationHelper helper = wb.getCreationHelper();
            date = wb.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            dateTime = wb.createCellStyle();
            dateTime.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        }
    }
}
