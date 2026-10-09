package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 7 — data-driven analysis & conclusion.
 *
 * Reads the experiment runs, computes honest aggregate statistics, and writes
 * {@code notes/analysis.json} + {@code notes/analysis.md}. Never fabricates a conclusion:
 * it only summarizes what is actually present in the run metrics.
 */
@Service
public class AnalysisEngine {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> analyze(PaperProject p, Path root) throws Exception {
        List<Map<String, Object>> runs = new ArrayList<>();
        Path runsDir = root.resolve("runs");
        List<Path> metricFiles = new ArrayList<>();
        if (Files.isDirectory(runsDir)) {
            try (var walk = Files.walk(runsDir, 3)) {
                walk.filter(f -> f.getFileName().toString().equals("metrics.json")).forEach(metricFiles::add);
            }
        }
        for (Path mf : metricFiles) {
            try {
                JsonNode n = mapper.readTree(Files.readString(mf, StandardCharsets.UTF_8));
                JsonNode res = n.path("results");
                String dataset = n.path("dataset").asText("");
                double mean = res.path("mean_val_accuracy").asDouble(Double.NaN);
                List<Double> seeds = new ArrayList<>();
                for (JsonNode row : res.path("per_seed")) {
                    if (row.has("val_accuracy")) seeds.add(row.path("val_accuracy").asDouble());
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("file", runsDir.relativize(mf).toString().replace('\\', '/'));
                r.put("dataset", dataset);
                r.put("meanValAccuracy", mean);
                r.put("seedStd", std(seeds));
                r.put("seeds", seeds.size());
                r.put("status", n.path("status").asText(""));
                r.put("synthetic", "synthetic".equalsIgnoreCase(dataset));
                runs.add(r);
            } catch (Exception ignored) {
            }
        }

        List<Double> means = new ArrayList<>();
        for (Map<String, Object> r : runs) {
            Object mv = r.get("meanValAccuracy");
            if (mv instanceof Number num && !Double.isNaN(num.doubleValue())) {
                means.add(num.doubleValue());
            }
        }
        boolean hasReal = runs.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("synthetic")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runCount", runs.size());
        out.put("datasets", runs.stream().map(r -> r.get("dataset")).distinct().toArray());
        out.put("hasRealMetrics", hasReal);
        out.put("runs", runs);
        if (!means.isEmpty()) {
            out.put("meanAcrossRuns", round(mean(means)));
            out.put("stdAcrossRuns", round(std(means)));
        } else {
            out.put("meanAcrossRuns", null);
        }
        out.put("conclusion", buildConclusion(out));

        StringBuilder md = new StringBuilder();
        md.append("# Analysis — ").append(p.getTitleWorking() == null ? p.getId() : p.getTitleWorking()).append("\n\n");
        md.append("- runs: ").append(runs.size()).append("\n");
        md.append("- real (non-synthetic) metrics: ").append(hasReal).append("\n\n");
        md.append("| run | dataset | mean acc | seed std | synthetic |\n");
        md.append("|-----|---------|----------|----------|-----------|\n");
        for (Map<String, Object> r : runs) {
            md.append("| ").append(r.get("file")).append(" | ").append(blank(r.get("dataset"))).append(" | ")
                    .append(fmt(r.get("meanValAccuracy"))).append(" | ").append(fmt(r.get("seedStd"))).append(" | ")
                    .append(r.get("synthetic")).append(" |\n");
        }
        if (!means.isEmpty()) {
            md.append("\nAcross ").append(means.size()).append(" run(s), mean±std accuracy = **")
                    .append(fmt(mean(means))).append("±").append(fmt(std(means))).append("**.\n");
        }
        md.append("\n> ").append(out.get("conclusion")).append("\n");

        Files.writeString(root.resolve("notes").resolve("analysis.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("notes").resolve("analysis.md"), md.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static String buildConclusion(Map<String, Object> out) {
        int n = out.get("runCount") instanceof Number num ? num.intValue() : 0;
        boolean real = Boolean.TRUE.equals(out.get("hasRealMetrics"));
        Object mean = out.get("meanAcrossRuns");
        if (n == 0) {
            return "尚无 run 的 metrics 落入 runs/；请先授权算力并跑通 SWEEP，再据此下结论。本阶段不基于合成数据做任何实证主张。";
        }
        String base = n + " 个 run";
        if (real) {
            return "检测到真实指标的 " + base + "。均值" + fmt(mean)
                    + " 仅对当前数据集/配置成立；若要声称优于 baseline 或 SOTA，必须跑同一数据与种子下的 baseline/变体对比矩阵与消融（见 COMPARE/ABLATE）。";
        }
        return base + " 均为合成/模板指标，仅用于链路自测，不代表真实实验结论。请接入算力跑真实数据集后重做分析。";
    }

    private static double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return Double.NaN;
        double s = 0; for (double x : xs) s += x; return s / xs.size();
    }

    private static double std(List<Double> xs) {
        if (xs == null || xs.size() < 2) return 0;
        double m = mean(xs), s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return Math.sqrt(s / xs.size());
    }

    private static String fmt(Object v) {
        if (v == null) return "n/a";
        if (v instanceof Double d) return String.format(java.util.Locale.ROOT, "%.4f", d);
        return String.valueOf(v);
    }

    private static String blank(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
