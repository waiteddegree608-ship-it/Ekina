package com.ekina.bootstrap.controller;

import com.ekina.application.permission.CapabilityGrant;
import com.ekina.application.permission.CapabilityService;
import com.ekina.application.permission.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private CapabilityService capabilityService;

    @PostMapping("/decide")
    public Map<String, Object> decide(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String messageId = String.valueOf(body.get("messageId"));
        String decision = String.valueOf(body.getOrDefault("decision", "denied"));
        Integer ttlHours = body.get("ttlHours") instanceof Number n ? n.intValue() : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = body.get("scope") instanceof Map ? (Map<String, Object>) body.get("scope") : null;
        return permissionService.decide(userId, messageId, decision, ttlHours, scope);
    }

    @GetMapping("/capabilities")
    public List<CapabilityGrant> capabilities(@RequestParam(defaultValue = "user-1") String userId) {
        return capabilityService.list(userId);
    }

    @PostMapping("/capabilities/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) {
        return Map.of("revoked", capabilityService.revoke(id), "id", id);
    }
}
