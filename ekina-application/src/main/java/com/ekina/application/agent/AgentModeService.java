package com.ekina.application.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user agent execution mode (PLAN / NORMAL / TURBO).
 *
 * <p>The mode is an in-memory runtime preference keyed by userId. It is NOT persisted to the
 * database — it describes how Ekina should behave in the current conversation/session and is
 * expected to be set interactively ("开计划模式" / "切 turbo") or via the UI/API.
 */
@Service
public class AgentModeService {

    private final Map<String, AgentMode> modes = new ConcurrentHashMap<>();

    @Value("${ekina.agent.mode:NORMAL}")
    private String defaultMode;

    /** Resolve the effective mode for a user (falls back to configured default). */
    public AgentMode current(String userId) {
        if (userId == null || userId.isBlank()) return AgentMode.from(defaultMode);
        return modes.getOrDefault(userId, AgentMode.from(defaultMode));
    }

    /** Set the mode for a user; returns the new effective mode. */
    public AgentMode set(String userId, AgentMode mode) {
        AgentMode m = mode == null ? AgentMode.from(defaultMode) : mode;
        if (userId != null && !userId.isBlank()) {
            modes.put(userId, m);
        }
        return m;
    }

    /** Clear a user's override, falling back to the configured default. */
    public AgentMode reset(String userId) {
        if (userId != null && !userId.isBlank()) {
            modes.remove(userId);
        }
        return current(userId);
    }

    public AgentMode defaultMode() {
        return AgentMode.from(defaultMode);
    }

    /** Simple status map for API/tool output. */
    public Map<String, Object> status(String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("mode", current(userId).name());
        m.put("defaultMode", AgentMode.from(defaultMode).name());
        m.put("override", userId != null && !userId.isBlank() && modes.containsKey(userId));
        return m;
    }
}
