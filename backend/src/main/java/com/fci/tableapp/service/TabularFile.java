package com.fci.tableapp.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Reads an uploaded CSV / Excel file row by row.
 * Values are String (CSV and text cells), BigDecimal, LocalDate, LocalDateTime, Boolean or null.
 */
final class TabularFile {

    private TabularFile() {
    }

    interface RowReader extends Closeable {
        /** Column names from the first row. */
        List<String> header();

        /** Next data row, or null at the end. Blank rows are skipped. */
        List<Object> next() throws IOException;
    }

    static boolean isExcel(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls");
    }

    static RowReader open(Path path, String fileName) throws IOException {
        return isExcel(fileName) ? new ExcelReader(path) : new CsvReader(path);
    }

    /** Header names: blanks become column_N, duplicates get a suffix. */
    private static List<String> cleanHeader(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null ? "" : raw.get(i).trim();
            if (h.isEmpty()) h = "column_" + (i + 1);
            String unique = h;
            for (int n = 2; !seen.add(unique.toLowerCase(Locale.ROOT)); n++) unique = h + "_" + n;
            out.add(unique);
        }
        return out;
    }

    private static boolean isBlank(List<Object> row) {
        for (Object v : row) {
            if (v != null && !(v instanceof String s && s.isBlank())) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ CSV

    private static final class CsvReader implements RowReader {
        private final CSVParser parser;
        private final Iterator<CSVRecord> it;
        private final List<String> header;

        CsvReader(Path path) throws IOException {
            Charset cs = detectCharset(path);
            char delimiter = detectDelimiter(path, cs);
            Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), cs), 64 * 1024);
            reader.mark(1);
            if (reader.read() != '﻿') reader.reset(); // skip UTF-8 BOM
            parser = CSVFormat.RFC4180.builder().setDelimiter(delimiter).build().parse(reader);
            it = parser.iterator();
            if (!it.hasNext()) {
                parser.close();
                throw new ImportException("The file is empty.");
            }
            header = cleanHeader(it.next().toList());
        }

        @Override
        public List<String> header() {
            return header;
        }

        @Override
        public List<Object> next() {
            while (it.hasNext()) {
                List<Object> row = new ArrayList<>(it.next().toList());
                if (!isBlank(row)) return row;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            parser.close();
        }

        /** UTF-8 if the start of the file is valid UTF-8, otherwise Windows-1252 (Excel "CSV" default). */
        private static Charset detectCharset(Path path) throws IOException {
            byte[] head = readHead(path, 1 << 20);
            int len = head.length;
            // do not cut a multi-byte character in half at the end of the sample
            int back = 0;
            while (back < 3 && len - back - 1 >= 0 && (head[len - back - 1] & 0xC0) == 0x80) back++;
            if (len - back - 1 >= 0 && (head[len - back - 1] & 0xC0) == 0xC0) back++;
            if (len == (1 << 20)) len -= back;
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(head, 0, len));
                return StandardCharsets.UTF_8;
            } catch (CharacterCodingException e) {
                return Charset.forName("windows-1252");
            }
        }

        /** The most frequent of , ; tab | in the first line (outside quotes). */
        private static char detectDelimiter(Path path, Charset cs) throws IOException {
            String head = new String(readHead(path, 64 * 1024), cs);
            Map<Character, Integer> counts = new LinkedHashMap<>();
            for (char c : new char[]{',', ';', '\t', '|'}) counts.put(c, 0);
            boolean quoted = false;
            for (char c : head.toCharArray()) {
                if (c == '"') quoted = !quoted;
                else if (!quoted && (c == '\n' || c == '\r')) break;
                else if (!quoted && counts.containsKey(c)) counts.merge(c, 1, Integer::sum);
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .orElse(',');
        }

        private static byte[] readHead(Path path, int max) throws IOException {
            try (InputStream in = Files.newInputStream(path)) {
                return in.readNBytes(max);
            }
        }
    }

    // ------------------------------------------------------------------ Excel

    /** Reads the first sheet, plus following sheets that have the same header (large exports are split). */
    private static final class ExcelReader implements RowReader {
        private final Workbook wb;
        private final List<String> header;
        private final List<String> rawHeader;
        private int sheetIndex = 0;
        private Iterator<Row> rows;

        ExcelReader(Path path) throws IOException {
            try {
                wb = WorkbookFactory.create(path.toFile(), null, true);
            } catch (RuntimeException e) {
                throw new ImportException("Could not read the Excel file: " + e.getMessage(), e);
            }
            if (wb.getNumberOfSheets() == 0) {
                wb.close();
                throw new ImportException("The Excel file has no sheets.");
            }
            Sheet first = wb.getSheetAt(0);
            rows = first.rowIterator();
            if (!rows.hasNext()) {
                wb.close();
                throw new ImportException("The first sheet is empty.");
            }
            rawHeader = headerOf(rows.next());
            header = cleanHeader(rawHeader);
        }

        private static List<String> headerOf(Row row) {
            DataFormatter fmt = new DataFormatter();
            List<String> h = new ArrayList<>();
            for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                Cell cell = row.getCell(c);
                h.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
            }
            while (!h.isEmpty() && h.get(h.size() - 1).isEmpty()) h.remove(h.size() - 1);
            return h;
        }

        @Override
        public List<String> header() {
            return header;
        }

        @Override
        public List<Object> next() {
            while (true) {
                while (rows.hasNext()) {
                    Row r = rows.next();
                    List<Object> row = new ArrayList<>(header.size());
                    for (int c = 0; c < header.size(); c++) row.add(value(r.getCell(c)));
                    if (!isBlank(row)) return row;
                }
                if (!nextSheet()) return null;
            }
        }

        private boolean nextSheet() {
            while (++sheetIndex < wb.getNumberOfSheets()) {
                Iterator<Row> it = wb.getSheetAt(sheetIndex).rowIterator();
                if (it.hasNext() && headerOf(it.next()).equals(rawHeader)) {
                    rows = it;
                    return true;
                }
                return false; // a different table – stop here
            }
            return false;
        }

        private static Object value(Cell cell) {
            if (cell == null) return null;
            CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
            return switch (type) {
                case STRING -> cell.getStringCellValue();
                case BOOLEAN -> cell.getBooleanCellValue();
                case NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        LocalDateTime dt = cell.getLocalDateTimeCellValue();
                        yield dt.toLocalTime().equals(LocalTime.MIDNIGHT) ? dt.toLocalDate() : dt;
                    }
                    // same digits Excel shows, without binary floating-point noise
                    yield new BigDecimal(NumberToTextConverter.toText(cell.getNumericCellValue()));
                }
                default -> null;
            };
        }

        @Override
        public void close() throws IOException {
            wb.close();
        }
    }
}
