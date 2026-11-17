package com.fci.tableapp.controller;

import com.fci.tableapp.service.ConnectionFailedException;
import com.fci.tableapp.service.DbException;
import com.fci.tableapp.service.ImportException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.fci.tableapp.service.SessionNotFoundException;
import com.fci.tableapp.service.TableNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;


@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TableNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TableNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DbException.class)
    public ResponseEntity<Map<String, String>> database(DbException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    /** Upload could not be imported (bad value, mapping, rejected rows) */
    @ExceptionHandler(ImportException.class)
    public ResponseEntity<Map<String, String>> importFailed(ImportException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large (limit 200 MB).");
    }

    /** Wrong host / password / database etc. while connecting */
    @ExceptionHandler(ConnectionFailedException.class)
    public ResponseEntity<Map<String, String>> connectionFailed(ConnectionFailedException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Session expired or server restarted: the UI goes back to the connect screen */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> sessionExpired(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage(), "code", "SESSION_EXPIRED"));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? status.getReasonPhrase() : message));
    }
}
