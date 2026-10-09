package com.ekina.application.paper;

/**
 * Pipeline stages for automated paper production.
 * Phase-1 implements: SCOUT(lite) → LIT → TEX → CITE_CHECK → PACK → GATE.
 * Later: REPRO / SWEEP / FIG bind to real experiment metrics.
 */
public enum PaperStage {
    SCOUT,
    LIT,
    REPRO,
    SWEEP,
    COMPARE,
    ABLATE,
    ANALYZE,
    FIG,
    TEX,
    CITE_CHECK,
    PACK,
    GATE,
    DONE,
    FAILED
}
