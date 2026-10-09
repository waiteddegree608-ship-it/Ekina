package com.ekina.bootstrap.controller;

import com.ekina.application.multiagent.Mission;
import com.ekina.application.multiagent.MissionOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/missions")
public class MissionController {

    @Autowired
    private MissionOrchestrator missionOrchestrator;

    @GetMapping
    public List<Mission> list(
            @RequestParam(defaultValue = "user-1") String userId,
            @RequestParam(defaultValue = "20") int limit) {
        return missionOrchestrator.list(userId, limit);
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id) {
        return missionOrchestrator.get(id)
                .<Object>map(m -> m)
                .orElse(Map.of("error", "not found", "id", id));
    }

    @PostMapping
    public Object accept(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        // Batch: { "goals": ["a","b"] } → concurrent missions
        if (body.get("goals") instanceof List<?> goalsRaw) {
            List<String> goals = new java.util.ArrayList<>();
            for (Object g : goalsRaw) {
                if (g != null && !String.valueOf(g).isBlank()) {
                    goals.add(String.valueOf(g).trim());
                }
            }
            int active = missionOrchestrator.countActive(userId);
            List<Mission> created = missionOrchestrator.acceptMany(userId, goals);
            return Map.of(
                    "created", created.size(),
                    "activeBefore", active,
                    "missions", created,
                    "queuedHint", active >= 2
                            ? "已有进行中的任务图，新项目会排队执行。"
                            : "可并行推进；细进度看侧栏项目时间轴。"
            );
        }
        String goal = String.valueOf(body.getOrDefault("goal", ""));
        Object nodes = body.get("nodesJson");
        if (nodes == null) {
            nodes = body.get("nodes");
        }
        String nodesJson = null;
        if (nodes instanceof String s && !s.isBlank()) {
            nodesJson = s;
        } else if (nodes != null) {
            try {
                nodesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(nodes);
            } catch (Exception ignored) {
            }
        }
        return missionOrchestrator.accept(userId, goal, nodesJson);
    }

    @PostMapping("/{id}/cancel")
    public Mission cancel(@PathVariable String id) {
        return missionOrchestrator.cancel(id);
    }
}
