package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hard gates for paper factory. Fail closed: no fake metrics / unverified cites.
 * Mode-aware honesty: smoke may pass on template metrics; research/reproduce require repro artifacts.
 */
@Service
public class GateKeeper {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ekina.paper.gate.min-lit:5}")
    private int minLit;

    @Value("${ekina.paper.gate.require-cite-ok:true}")
    private boolean requireCiteOk;

    @Value("${ekina.paper.gate.require-zip:true}")
    private boolean requireZip;

    @Value("${ekina.paper.gate.require-metrics-for-claims:true}")
    private boolean requireMetricsForClaims;

    @org.springframework.beans.factory.annotation.Autowired
    private PaperAuditService paperAuditService;

    public Map<String, Object> evaluate(Path root, Map<String, Object> citeReport) {
        return evaluate(root, citeReport, PaperMode.RESEARCH);
    }

    public Map<String, Object> evaluate(Path root, Map<String, Object> citeReport, PaperMode mode) {
        if (mode == null) mode = PaperMode.RESEARCH;
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        int score = 0;
        int max = 0;

        // LIT catalog
        max += 20;
        Path catalog = root.resolve("lit").resolve("catalog.json");
        int litCount = 0;
        try {
            if (Files.isRegularFile(catalog)) {
                var node = mapper.readTree(Files.readString(catalog, StandardCharsets.UTF_8));
                if (node.isArray()) litCount = node.size();
                else if (node.has("papers") && node.get("papers").isArray()) litCount = node.get("papers").size();
            }
        } catch (Exception ignored) {
        }
        boolean litOk = litCount >= minLit;
        checks.add(check("lit_count", litOk, "papers=" + litCount + " min=" + minLit));
        if (litOk) score += 20;

        // Citation verification
        max += 25;
        boolean citeOk = citeReport != null && Boolean.TRUE.equals(citeReport.get("ok"));
        int verified = citeReport == null ? 0 : toInt(citeReport.get("verified"));
        checks.add(check("citations_verified", citeOk || (!requireCiteOk && verified > 0),
                "verified=" + verified + " failed=" + (citeReport == null ? "?" : citeReport.get("failed"))));
        if (citeOk) score += 25;
        else if (verified > 0) score += 10;

        // TEX exists
        max += 10;
        boolean texOk = Files.isRegularFile(root.resolve("tex").resolve("main.tex"))
                && Files.isRegularFile(root.resolve("tex").resolve("refs.bib"));
        checks.add(check("tex_present", texOk, texOk ? "main.tex+refs.bib" : "missing"));
        if (texOk) score += 10;

        // ZIP
        max += 10;
        boolean zipOk = false;
        try {
            Path outbox = root.resolve("outbox");
            if (Files.isDirectory(outbox)) {
                try (var s = Files.list(outbox)) {
                    zipOk = s.anyMatch(p -> p.getFileName().toString().endsWith(".zip"));
                }
            }
        } catch (Exception ignored) {
        }
        checks.add(check("zip_present", zipOk || !requireZip, zipOk ? "outbox zip ok" : "no zip"));
        if (zipOk) score += 10;

        // Metrics honesty
        max += 15;
        boolean hasMetrics = false;
        boolean syntheticMetrics = true;
        try {
            Path runs = root.resolve("runs");
            JsonNode latestNode = null;
            if (Files.isDirectory(runs)) {
                try (var walk = Files.walk(runs, 3)) {
                    List<Path> mfs = walk.filter(p -> p.getFileName().toString().equals("metrics.json")).toList();
                    hasMetrics = !mfs.isEmpty();
                    // Prefer runs/latest_metrics.json (the summary the paper cites); else the newest run file.
                    Path latest = runs.resolve("latest_metrics.json");
                    if (Files.isRegularFile(latest)) {
                        latestNode = mapper.readTree(Files.readString(latest, StandardCharsets.UTF_8));
                    } else if (!mfs.isEmpty()) {
                        latestNode = mapper.readTree(Files.readString(mfs.get(mfs.size() - 1), StandardCharsets.UTF_8));
                    }
                }
            }
            if (latestNode != null) {
                syntheticMetrics = isSyntheticRun(latestNode);
            }
        } catch (Exception ignored) {
        }
        boolean claimsWithoutMetrics = false;
        try {
            String tex = Files.readString(root.resolve("tex").resolve("main.tex"), StandardCharsets.UTF_8);
            if (!hasMetrics && requireMetricsForClaims) {
                String lower = tex.toLowerCase();
                if ((lower.contains("outperform") || lower.contains("accuracy") || lower.contains("sota"))
                        && !lower.contains("placeholder")) {
                    claimsWithoutMetrics = true;
                }
            }
        } catch (Exception ignored) {
        }
        // SMOKE may pass on template/synthetic metrics; RESEARCH/REPRODUCE MUST have real non-synthetic
        // metrics before any empirical claim is accepted. This closes the "zero-experiment pass" hole.
        boolean metricsGate;
        if (mode == PaperMode.SMOKE) {
            metricsGate = true;
        } else {
            metricsGate = hasMetrics && !syntheticMetrics;
        }
        checks.add(check("no_fake_metrics", metricsGate,
                mode == PaperMode.SMOKE
                        ? "smoke mode allows template metrics"
                        : (hasMetrics
                            ? (syntheticMetrics ? "metrics.json present (SYNTHETIC/template) — NOT acceptable for " + mode
                                : "real metrics present")
                            : "no empirical metrics — research/reproduce requires real runs")));
        if (metricsGate) score += 15;

        // Outline
        max += 5;
        boolean outlineOk = Files.isRegularFile(root.resolve("notes").resolve("outline.md"));
        checks.add(check("outline_present", outlineOk || mode == PaperMode.SMOKE,
                outlineOk ? "notes/outline.md" : "missing outline"));
        if (outlineOk) score += 5;

        // Architecture diagram
        max += 5;
        boolean diagramOk = Files.isRegularFile(root.resolve("figures").resolve("architecture.mmd"))
                || Files.isRegularFile(root.resolve("figures").resolve("architecture.tex"));
        checks.add(check("architecture_diagram", diagramOk || mode == PaperMode.SMOKE,
                diagramOk ? "figures/architecture.*" : "missing diagram"));
        if (diagramOk) score += 5;

        // Repro artifact (required for reproduce; preferred for research)
        max += 10;
        Map<String, Object> repro = readJson(root.resolve("repro").resolve("repro_report.json"));
        boolean reproCloned = Boolean.TRUE.equals(repro.get("ok"))
                && String.valueOf(repro.getOrDefault("status", "")).startsWith("cloned");
        boolean reproRequired = mode == PaperMode.REPRODUCE;
        boolean reproOk = reproCloned || !reproRequired;
        if (mode == PaperMode.RESEARCH && !reproCloned) {
            reproOk = true;
        }
        checks.add(check("repro_upstream", reproOk,
                reproCloned ? ("cloned " + repro.get("repo")) : ("status=" + repro.get("status"))));
        if (reproCloned) score += 10;
        else if (mode == PaperMode.SMOKE) score += 5;

        // Baseline smoke (bonus / soft requirement for reproduce)
        max += 10;
        Map<String, Object> baseline = readJson(root.resolve("repro").resolve("baseline_report.json"));
        boolean baselineOk = Boolean.TRUE.equals(baseline.get("ok"));
        String baselineStatus = String.valueOf(baseline.getOrDefault("status", "missing"));
        boolean baselineRequired = mode == PaperMode.REPRODUCE && reproCloned;
        checks.add(check("baseline_smoke", baselineOk || !baselineRequired,
                "status=" + baselineStatus + " metricMatch=" + baseline.get("metricMatch")));
        if (baselineOk) score += 10;
        else if (mode == PaperMode.SMOKE) score += 3;

        if (paperAuditService != null) {
            List<Map<String, Object>> layout = paperAuditService.pipelineChecks(root);
            for (Map<String, Object> c : layout) {
                checks.add(c);
                max += 5;
                if (Boolean.TRUE.equals(c.get("ok"))) score += 5;
            }
        }

        boolean pass = litOk && texOk && (zipOk || !requireZip) && metricsGate
                && (citeOk || !requireCiteOk);
        if (paperAuditService != null && mode != PaperMode.SMOKE
                && !paperAuditService.pipelinePass(root, mode)) {
            pass = false;
        }
        if (requireCiteOk && !citeOk) pass = false;
        if (reproRequired && !reproCloned) pass = false;
        if (baselineRequired && !baselineOk) pass = false;
        if (mode != PaperMode.SMOKE && !outlineOk) pass = false;
        // Reproduce mini-goal: must have non-synthetic metrics pulled from a real run.
        if (mode == PaperMode.REPRODUCE && (!hasMetrics || syntheticMetrics)) {
            pass = false;
            checks.add(check("reproduce_real_metrics", false,
                    hasMetrics ? "synthetic/template metrics not enough for reproduce" : "missing metrics.json"));
        } else if (mode == PaperMode.REPRODUCE) {
            checks.add(check("reproduce_real_metrics", true, "non-synthetic metrics present"));
        }

        String honesty;
        if (mode == PaperMode.SMOKE) {
            honesty = "smoke";
        } else if (reproCloned && Boolean.TRUE.equals(baseline.get("metricMatch"))) {
            honesty = "research_with_metric_match";
        } else if (reproCloned && baselineOk && hasMetrics && !syntheticMetrics) {
            honesty = "research_with_real_metrics";
        } else if (reproCloned && baselineOk) {
            honesty = "research_repro_baseline_smoke_metrics_template_or_none";
        } else if (reproCloned) {
            honesty = "research_repro_inventoried_metrics_template_or_none";
        } else {
            honesty = "research_partial_no_upstream_clone";
        }

        report.put("pass", pass);
        report.put("score", score);
        report.put("maxScore", max);
        report.put("checks", checks);
        report.put("litCount", litCount);
        report.put("hasMetrics", hasMetrics);
        report.put("syntheticMetrics", syntheticMetrics);
        report.put("mode", mode.name());
        report.put("honestyLevel", honesty);
        report.put("reproCloned", reproCloned);
        return report;
    }

    private Map<String, Object> readJson(Path p) {
        try {
            if (!Files.isRegularFile(p)) return Map.of();
            return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Schema-aware synthetic detection. A run is synthetic unless it carries a real dataset name AND at
     * least one computed per-seed numeric metric (train loss / val acc / loss / fid / is). This prevents
     * a fake run from being admitted merely because its {@code dataset} label isn't literally "synthetic",
     * while still accepting real CPU/GPU runs (diffusion reports fid, accuracy runs report val_accuracy).
     */
    private static boolean isSyntheticRun(JsonNode n) {
        if (n == null) return true;
        String ds = n.path("dataset").asText("");
        String dsl = ds.toLowerCase(Locale.ROOT).trim();
        if (dsl.isBlank() || dsl.equals("null")
                || dsl.contains("synthetic") || dsl.contains("template") || dsl.contains("smoke")
                || dsl.contains("fake") || dsl.contains("placeholder") || dsl.contains("random")) {
            return true;
        }
        JsonNode res = n.path("results");
        JsonNode perSeed = res.path("per_seed");
        if (!perSeed.isArray() || perSeed.size() == 0) return true;
        for (JsonNode row : perSeed) {
            if (row.path("train_loss").isNumber() || row.path("val_accuracy").isNumber()
                    || row.path("val_loss").isNumber() || row.path("loss").isNumber()
                    || row.path("fid").isNumber() || row.path("is_score").isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
