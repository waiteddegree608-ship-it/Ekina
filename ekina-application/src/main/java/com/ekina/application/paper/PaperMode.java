package com.ekina.application.paper;

/**
 * Pipeline honesty levels.
 * <ul>
 *   <li>{@link #SMOKE} — template train + optional synthetic metrics (link test only).</li>
 *   <li>{@link #REPRODUCE} — pick open-source paper target, clone, inventory, attempt baseline path.</li>
 *   <li>{@link #RESEARCH} — reproduce foundation + innovation plan + outline/figures/writing scaffold.</li>
 * </ul>
 */
public enum PaperMode {
    SMOKE,
    REPRODUCE,
    RESEARCH;

    public static PaperMode from(String raw) {
        if (raw == null || raw.isBlank()) return RESEARCH;
        String s = raw.trim().toUpperCase().replace('-', '_');
        return switch (s) {
            case "SMOKE", "QUICK", "TEMPLATE" -> SMOKE;
            case "REPRODUCE", "REPRO", "BASELINE" -> REPRODUCE;
            case "RESEARCH", "FULL", "INNOVATE" -> RESEARCH;
            default -> RESEARCH;
        };
    }

    public boolean needsUpstream() {
        return this == REPRODUCE || this == RESEARCH;
    }

    public boolean allowSyntheticMetricsAsPass() {
        return this == SMOKE;
    }
}
