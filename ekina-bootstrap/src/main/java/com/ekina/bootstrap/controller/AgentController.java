package com.ekina.bootstrap.controller;

import com.ekina.application.agentreg.AgentManifest;
import com.ekina.application.agentreg.AgentRegistry;
import com.ekina.application.agentreg.CapabilityPackCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ekina character/agent registry (not the OpenCode crew under /api/agents).
 */
@RestController
@RequestMapping("/api/kymara/agents")
public class AgentController {

    @Autowired
    private AgentRegistry agentRegistry;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "user-1") String userId,
            @RequestParam(defaultValue = "false") boolean enabledOnly
    ) {
        return agentRegistry.listForApi(userId, enabledOnly);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id,
                                 @RequestParam(defaultValue = "user-1") String userId) {
        return agentRegistry.find(id)
                .<ResponseEntity<?>>map(m -> {
                    Map<String, Object> map = m.toMap();
                    map.put("personaMarkdown", agentRegistry.loadPersonaMarkdown(id));
                    return ResponseEntity.ok(map);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not found")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body) {
        try {
            AgentManifest created = agentRegistry.create(body == null ? Map.of() : body);
            Map<String, Object> map = created.toMap();
            map.put("personaMarkdown", agentRegistry.loadPersonaMarkdown(created.getId()));
            return ResponseEntity.status(HttpStatus.CREATED).body(map);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            AgentManifest updated = agentRegistry.patch(id, body == null ? Map.of() : body);
            Map<String, Object> map = updated.toMap();
            map.put("personaMarkdown", agentRegistry.loadPersonaMarkdown(id));
            return ResponseEntity.ok(map);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meta/capability-packs")
    public List<Map<String, Object>> packs() {
        return CapabilityPackCatalog.listPacks();
    }

    /** Alias matching plan path GET /api/capability-packs */
    @GetMapping(path = "", params = "packs")
    public Map<String, Object> packsQuery() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packs", CapabilityPackCatalog.listPacks());
        return m;
    }
}
