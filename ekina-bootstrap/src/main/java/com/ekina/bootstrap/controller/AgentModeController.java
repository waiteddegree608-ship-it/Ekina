package com.ekina.bootstrap.controller;

import com.ekina.application.agent.AgentMode;
import com.ekina.application.agent.AgentModeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Runtime agent execution-mode control (PLAN / NORMAL / TURBO).
 * Lets the UI / API read and switch Ekina's working mode without a chat turn.
 */
@RestController
@RequestMapping("/api/agent/mode")
public class AgentModeController {

    @Autowired
    private AgentModeService agentModeService;

    @GetMapping
    public Map<String, Object> get(@RequestParam(name = "userId", defaultValue = "user-1") String userId) {
        return agentModeService.status(userId);
    }

    @PostMapping
    public Map<String, Object> set(@RequestParam(name = "userId", defaultValue = "user-1") String userId,
                                   @RequestBody Map<String, Object> body) {
        String mode = String.valueOf(body.getOrDefault("mode", "NORMAL"));
        agentModeService.set(userId, AgentMode.from(mode));
        return agentModeService.status(userId);
    }

    @DeleteMapping
    public Map<String, Object> reset(@RequestParam(name = "userId", defaultValue = "user-1") String userId) {
        agentModeService.reset(userId);
        return agentModeService.status(userId);
    }
}
