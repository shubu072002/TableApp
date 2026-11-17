package com.fci.tableapp.controller;

import com.fci.tableapp.model.ImportPreview;
import com.fci.tableapp.model.ImportRequest;
import com.fci.tableapp.model.ImportResult;
import com.fci.tableapp.service.ConnectionRegistry;
import com.fci.tableapp.service.DbSession;
import com.fci.tableapp.service.ImportService;
import com.fci.tableapp.service.UploadRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload a CSV / Excel file into a table of the connected database.
 * 1. POST   /api/connections/{id}/imports            (multipart "file") -> preview
 * 2. POST   /api/connections/{id}/imports/{uploadId} (JSON ImportRequest) -> result
 *    DELETE /api/connections/{id}/imports/{uploadId} -> discard the uploaded file
 */
@RestController
@RequestMapping("/api/connections/{id}/imports")
public class ImportController {

    private final ConnectionRegistry registry;
    private final UploadRegistry uploads;
    private final ImportService importService;

    public ImportController(ConnectionRegistry registry, UploadRegistry uploads, ImportService importService) {
        this.registry = registry;
        this.uploads = uploads;
        this.importService = importService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview upload(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        DbSession s = registry.get(id);
        UploadRegistry.Upload upload = uploads.store(s.id(), file);
        try {
            return importService.preview(s, upload);
        } catch (RuntimeException e) {
            uploads.remove(upload.id());
            throw e;
        }
    }

    @PostMapping("/{uploadId}")
    public ImportResult importData(@PathVariable String id, @PathVariable String uploadId,
                                   @RequestBody ImportRequest request) {
        DbSession s = registry.get(id);
        UploadRegistry.Upload upload = uploads.get(s.id(), uploadId);
        ImportResult result = importService.importData(s, upload, request);
        uploads.remove(uploadId); // keep the file on failure so the user can fix the mapping and retry
        return result;
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> discard(@PathVariable String id, @PathVariable String uploadId) {
        DbSession s = registry.get(id);
        uploads.get(s.id(), uploadId);
        uploads.remove(uploadId);
        return ResponseEntity.noContent().build();
    }
}
