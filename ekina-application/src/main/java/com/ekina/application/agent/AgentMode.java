package com.ekina.application.agent;

import java.util.Locale;

/**
 * IDE-agent style execution modes for Ekina's main conversational agent.
 *
 * <p>{@link #PLAN}   — propose first: the agent may think and plan but does NOT execute tools
 *                      until the owner approves the plan card. Safe / deliberate.
 * <p>{@link #NORMAL} — default: standard ReAct loop with tool calling and the usual permission gates.
 * <p>{@link #TURBO}  — faster & more autonomous: more tool-stepping headroom per turn, less
 *                      pre-emptive hand-holding; explicit hard permission requests still gate.
 */
public enum AgentMode {
    PLAN,
    NORMAL,
    TURBO;

    public static AgentMode from(String raw) {
        if (raw == null || raw.isBlank()) return NORMAL;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (s) {
            case "PLAN", "PLANNING", "PROPOSE", "SAFE", "计划", "规划", "计划模式", "规划模式" -> PLAN;
            case "TURBO", "AUTO", "FAST", "涡轮", "涡轮模式", "加速" -> TURBO;
            default -> NORMAL;
        };
    }
}
