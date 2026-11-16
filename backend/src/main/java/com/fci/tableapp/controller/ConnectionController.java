package com.fci.tableapp.controller;

import com.fci.tableapp.model.ConnectRequest;
import com.fci.tableapp.service.ConnectionRegistry;
import com.fci.tableapp.service.DbSession;
import com.fci.tableapp.service.TableService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionRegistry registry;
    private final TableService tableService;

    public ConnectionController(ConnectionRegistry registry, TableService tableService) {
        this.registry = registry;
        this.tableService = tableService;
    }

    /** POST /api/connections  {dbType, host, port, database, username, password, schema} -> {id, info} */
    @PostMapping
    public Map<String, Object> connect(@RequestBody ConnectRequest request) {
        DbSession session = registry.open(request);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", session.id());
            body.put("info", tableService.connectionInfo(session));
            return body;
        } catch (RuntimeException e) {
            registry.close(session.id());
            throw e;
        }
    }

    /** GET /api/connections/{id}  -> connection info (also tells the UI the session is still alive) */
    @GetMapping("/{id}")
    public Map<String, String> info(@PathVariable String id) {
        return tableService.connectionInfo(registry.get(id));
    }

    /** DELETE /api/connections/{id}  -> disconnect */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@PathVariable String id) {
        registry.close(id);
        return ResponseEntity.noContent().build();
    }
}
