package com.ekina.bootstrap.controller;

import com.ekina.application.project.ProjectService;
import com.ekina.application.project.ProjectTimelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.ekina.application.project.ResearchPlan;
import com.ekina.application.project.ResearchLedger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectTimelineService projectTimelineService;

    @Autowired
    private com.ekina.application.project.ProjectBoardService projectBoardService;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(name = "userId", defaultValue = "user-1") String userId) {
        return projectService.list(userId).stream()
                .map(projectService::toView)
                .collect(Collectors.toList());
    }

    @PostMapping("/research")
    public Map<String, Object> createResearch(@RequestBody Map<String, Object> body) {
        String title = ResearchPlan.required(body.get("brief"), 2000);
        String entry = ResearchPlan.required(body.get("entry"), 40);
        Map<String, Object> settings = ResearchPlan.collaboration(body.getOrDefault("collaboration", Map.of()));
        var p = projectService.createResearch(String.valueOf(body.getOrDefault("userId", "user-1")), title,
                entry, ResearchPlan.required(body.get("requestId"), 80));
        projectBoardService.initializeResearch(p.getId(), title, entry, settings);
        return projectService.toView(p);
    }

    @PutMapping("/{id}/collaboration")
    public Map<String, Object> collaboration(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        return projectBoardService.setCollaboration(id, body);
    }

    @PostMapping("/{id}/guidance")
    public Map<String, Object> guidance(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        return projectBoardService.addGuidance(id, ResearchPlan.text(body.get("target"), 240),
                ResearchPlan.required(body.get("text"), 4000), ResearchPlan.required(body.get("requestId"), 80));
    }

    private void requireProject(String id) {
        if (projectService.get(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidInput(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(ResearchLedger.Conflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> researchConflict(ResearchLedger.Conflict e) {
        return Map.of("error", e.getMessage());
    }

    @PostMapping("/{id}/research-records")
    public Map<String, Object> researchRecords(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        return projectBoardService.upsert(id, Map.of("researchUpdate", body));
    }

    /**
     * Owner-facing dock (Cursor-repos style): portfolios / papers / non-internal missions.
     * Fine-grained tasks stay in the full list for Ekina's internal ledger.
     */
    @GetMapping("/dock")
    public List<Map<String, Object>> dock(
            @RequestParam(name = "userId", defaultValue = "user-1") String userId,
            @RequestParam(name = "contactId", defaultValue = "ekina") String contactId) {
        return projectService.listDock(userId, contactId).stream()
                .map(projectService::toView)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return projectService.get(id)
                .map(projectService::toView)
                .orElseGet(() -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", "not found");
                    err.put("id", id);
                    return err;
                });
    }

    @GetMapping("/{id}/timeline")
    public Map<String, Object> timeline(@PathVariable String id) {
        return projectTimelineService.timeline(id);
    }

    @GetMapping("/{id}/board")
    public Map<String, Object> board(@PathVariable String id) {
        return projectBoardService.get(id);
    }

    @PutMapping("/{id}/board")
    public Map<String, Object> upsertBoard(
            @PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return projectBoardService.upsert(id, patch);
    }

    @PostMapping("/{id}/board/events")
    public Map<String, Object> appendBoardEvent(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String title = body == null ? "" : String.valueOf(body.getOrDefault("title", ""));
        String detail = body == null ? "" : String.valueOf(body.getOrDefault("detail", ""));
        String status = body == null ? "note" : String.valueOf(body.getOrDefault("status", "note"));
        return projectBoardService.appendEvent(id, title, detail, status);
    }
}
