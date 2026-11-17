package com.fci.tableapp.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps uploaded files in a private temp folder between "preview" and "import",
 * so a large file is sent only once. Each upload belongs to one DB session.
 */
@Component
public class UploadRegistry {

    private static final Logger log = LoggerFactory.getLogger(UploadRegistry.class);
    private static final Set<String> ALLOWED = Set.of("csv", "txt", "tsv", "xlsx", "xls");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An uploaded file waiting to be imported. */
    public static final class Upload {
        private final String id;
        private final String sessionId;
        private final Path path;
        private final String fileName;
        private final long createdAt = System.currentTimeMillis();
        private volatile List<ColumnStats> stats = List.of();

        Upload(String id, String sessionId, Path path, String fileName) {
            this.id = id;
            this.sessionId = sessionId;
            this.path = path;
            this.fileName = fileName;
        }

        public String id() {
            return id;
        }

        public Path path() {
            return path;
        }

        public String fileName() {
            return fileName;
        }

        List<ColumnStats> stats() {
            return stats;
        }

        void stats(List<ColumnStats> s) {
            stats = s;
        }
    }

    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Path dir;
    private final long expiryMillis;

    public UploadRegistry(@Value("${app.import.expiry-minutes:30}") long expiryMinutes) throws IOException {
        this.dir = Files.createTempDirectory("tableapp-uploads");
        this.expiryMillis = expiryMinutes * 60_000;
    }

    public Upload store(String sessionId, MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "upload.csv"
                : Path.of(file.getOriginalFilename()).getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type ." + ext + " – upload a .csv, .txt, .tsv, .xlsx or .xls file");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty.");
        }
        String id = newId();
        Path target = dir.resolve(id + "." + ext);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new ImportException("Could not save the uploaded file: " + e.getMessage(), e);
        }
        Upload u = new Upload(id, sessionId, target, name);
        uploads.put(id, u);
        return u;
    }

    public Upload get(String sessionId, String uploadId) {
        Upload u = uploadId == null ? null : uploads.get(uploadId);
        if (u == null || !u.sessionId.equals(sessionId)) {
            throw new ImportException("The uploaded file has expired. Please choose the file again.");
        }
        return u;
    }

    public void remove(String uploadId) {
        Upload u = uploadId == null ? null : uploads.remove(uploadId);
        if (u != null) {
            try {
                Files.deleteIfExists(u.path);
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", u.path, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void deleteExpired() {
        long cutoff = System.currentTimeMillis() - expiryMillis;
        uploads.values().stream().filter(u -> u.createdAt < cutoff).map(Upload::id).toList().forEach(this::remove);
    }

    @PreDestroy
    void deleteAll() {
        uploads.keySet().forEach(this::remove);
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // folder not empty / in use – the OS cleans temp eventually
        }
    }

    private static String newId() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
