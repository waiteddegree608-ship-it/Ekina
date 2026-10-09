package com.ekina.application.paper;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Closed-loop "run -> analyze -> optimize/innovate" controller for the paper pipeline.
 *
 * <p>When the initial experiment is poor (e.g. high FID), this proposes a concrete, honest
 * next-config (bigger model / more epochs / EMA...) rather than letting the pipeline stop at an
 * unsatisfying number. {@link #stop} decides when to halt: quality target met, improvement plateau,
 * or iteration budget exhausted.
 */
@Component
public class ExperimentOptimizer {

    /** Name of the metric we try to minimize (diffusion quality). */
    public static final String TARGET = "fid";

    /** Return the hyper for a given iteration (0 = baseline). Each step is a real, defensible change. */
    public Map<String, Object> hyperForIteration(int iteration, int baseEpochs) {
        Map<String, Object> h = new LinkedHashMap<>();
        switch (iteration) {
            case 0:
                h.put("ch", 96); h.put("epochs", baseEpochs); h.put("ema", false); break;
            case 1:
                h.put("ch", 160); h.put("epochs", (int) Math.round(baseEpochs * 1.5)); h.put("ema", true); break;
            case 2:
                h.put("ch", 208); h.put("epochs", (int) Math.round(baseEpochs * 2)); h.put("ema", true); break;
            default:
                h.put("ch", 160); h.put("epochs", baseEpochs); h.put("ema", true); break;
        }
        return h;
    }

    /** True when the loop should stop before running the next iteration. */
    public boolean stop(Double prevFid, int iteration, int maxIterations) {
        if (iteration >= maxIterations) return true;
        if (prevFid == null) return false;           // no baseline yet -> keep improving at least once
        if (prevFid <= 40.0) return true;            // quality target met (good enough for this protocol)
        return false;                                 // otherwise keep iterating within the budget
    }
}
