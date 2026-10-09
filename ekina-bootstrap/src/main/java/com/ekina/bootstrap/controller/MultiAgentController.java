package com.ekina.bootstrap.controller;

import com.ekina.application.multiagent.AgentTask;
import com.ekina.application.multiagent.MultiAgentOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class MultiAgentController {

    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @GetMapping
    public Map<String, Object> status() {
        return orchestrator.status();
    }

    @GetMapping("/live")
    public Map<String, Object> live(@RequestParam(defaultValue = "user-1") String userId) {
        return orchestrator.live(userId);
    }

    @GetMapping("/tasks")
    public List<AgentTask> tasks(
            @RequestParam(defaultValue = "user-1") String userId,
            @RequestParam(defaultValue = "20") int limit) {
        return orchestrator.list(userId, limit);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return orchestrator.get(id)
                .map(t -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", t.getId() == null ? "" : t.getId());
                    m.put("status", t.getStatus() == null ? "" : t.getStatus());
                    m.put("assignee", t.getAssignee() == null ? "" : t.getAssignee().name());
                    m.put("title", t.getTitle() == null ? "" : t.getTitle());
                    m.put("brief", t.getBrief() == null ? "" : t.getBrief());
                    m.put("result", t.getResult() == null ? "" : t.getResult());
                    m.put("error", t.getError() == null ? "" : t.getError());
                    m.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt());
                    m.put("startedAt", t.getStartedAt() == null ? "" : t.getStartedAt());
                    m.put("finishedAt", t.getFinishedAt() == null ? "" : t.getFinishedAt());
                    m.put("meta", t.getMeta() == null ? java.util.Map.of() : t.getMeta());
                    return m;
                })
                .orElse(Map.of("error", "not found", "id", id));
    }

    @PostMapping("/tasks")
    public AgentTask dispatch(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String assignee = String.valueOf(body.getOrDefault("assignee", "CODER"));
        String title = String.valueOf(body.getOrDefault("title", "task"));
        String brief = String.valueOf(body.getOrDefault("brief", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = body.get("meta") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        return orchestrator.dispatch(userId, assignee, title, brief, meta);
    }

    /** Clear stuck queued/running tasks from the console UI. */
    @PostMapping("/tasks/cancel-stuck")
    public Map<String, Object> cancelStuck(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        long olderThanSec = 60;
        Object o = body.get("olderThanSec");
        if (o instanceof Number n) {
            olderThanSec = n.longValue();
        } else if (o != null) {
            try { olderThanSec = Long.parseLong(String.valueOf(o)); } catch (Exception ignored) {}
        }
        return orchestrator.cancelStuck(userId, olderThanSec);
    }
}
