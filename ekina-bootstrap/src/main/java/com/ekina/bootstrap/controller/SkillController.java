package com.ekina.bootstrap.controller;

import com.ekina.application.skill.SkillDefinition;
import com.ekina.application.skill.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists markdown skill playbooks loaded by {@link SkillService}.
 * Distinct from {@code GET /api/mcp/skills} which returns AgentToolbox tool specs.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    @Autowired
    private SkillService skillService;

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillDefinition s : skillService.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId() == null ? "" : s.getId());
            row.put("title", s.getTitle() == null ? "" : s.getTitle());
            row.put("path", s.getPath() == null ? "" : s.getPath());
            row.put("tools", s.getTools() == null ? List.of() : s.getTools());
            row.put("triggers", s.getTriggers() == null ? List.of() : s.getTriggers());
            out.add(row);
        }
        return out;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        skillService.reload();
        return Map.of("reloaded", true, "count", skillService.list().size());
    }
}
