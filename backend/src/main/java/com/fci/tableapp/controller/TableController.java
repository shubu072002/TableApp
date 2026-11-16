package com.fci.tableapp.controller;

import com.fci.tableapp.model.TableDetails;
import com.fci.tableapp.model.TableInfo;
import com.fci.tableapp.service.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Table endpoints, always scoped to one open connection: /api/connections/{id}/tables/... */
@RestController
@RequestMapping("/api/connections/{id}/tables")
public class TableController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ConnectionRegistry registry;
    private final TableService tableService;
    private final CsvExporter csv;
    private final ExcelExporter excel;

    public TableController(ConnectionRegistry registry, TableService tableService,
                           CsvExporter csv, ExcelExporter excel) {
        this.registry = registry;
        this.tableService = tableService;
        this.csv = csv;
        this.excel = excel;
    }

    /** GET .../tables  -> list of all tables */
    @GetMapping
    public List<TableInfo> list(@PathVariable String id) {
        return tableService.listTables(registry.get(id));
    }

    /** GET .../tables/details?schema=HR&table=EMPLOYEES  -> columns + row count */
    @GetMapping("/details")
    public TableDetails details(@PathVariable String id,
                                @RequestParam(required = false) String schema,
                                @RequestParam String table) {
        DbSession s = registry.get(id);
        return tableService.details(s, tableService.findTable(s, schema, table));
    }

    /** GET .../tables/download?schema=HR&table=EMPLOYEES&format=csv|xlsx  -> file (all rows, all columns) */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id,
                                                          @RequestParam(required = false) String schema,
                                                          @RequestParam String table,
                                                          @RequestParam(defaultValue = "csv") String format) {
        TableExporter exporter = switch (format.toLowerCase()) {
            case "csv" -> csv;
            case "xlsx", "excel" -> excel;
            default -> throw new IllegalArgumentException("Unsupported format: " + format + " (use csv or xlsx)");
        };
        DbSession s = registry.get(id);
        // validate before streaming starts, so a bad name gets a proper 404
        TableInfo info = tableService.findTable(s, schema, table);

        String fileName = info.name() + "_" + LocalDateTime.now().format(STAMP) + "." + exporter.extension();
        StreamingResponseBody body = out -> tableService.export(s, info, exporter, out);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(exporter.contentType()))
                .body(body);
    }
}
