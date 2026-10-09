package com.ekina.bootstrap.controller;

import com.ekina.application.paper.PaperOrchestrator;
import com.ekina.application.paper.PaperProject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/papers")
public class PaperController {

    @Autowired
    private PaperOrchestrator paperOrchestrator;

    @GetMapping
    public List<PaperProject> list(
            @RequestParam(name = "userId", defaultValue = "user-1") String userId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return paperOrchestrator.list(userId, limit);
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id) {
        return paperOrchestrator.get(id)
                .<Object>map(p -> p)
                .orElse(Map.of("error", "not found", "id", id));
    }

    @PostMapping
    public PaperProject start(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String domain = String.valueOf(body.getOrDefault("domain", ""));
        String venueTier = String.valueOf(body.getOrDefault("venueTier", "CCF-C"));
        String titleHint = body.get("titleHint") == null ? null : String.valueOf(body.get("titleHint"));
        String mode = body.get("mode") == null ? null : String.valueOf(body.get("mode"));
        String seedRepo = body.get("seedRepo") == null ? null : String.valueOf(body.get("seedRepo"));
        boolean autoRun = body.get("autoRun") == null || Boolean.parseBoolean(String.valueOf(body.get("autoRun")));
        return paperOrchestrator.start(userId, domain, venueTier, titleHint, mode, seedRepo, autoRun);
    }

    @PostMapping("/{id}/run")
    public PaperProject run(@PathVariable String id) {
        return paperOrchestrator.runToGate(id);
    }
}
