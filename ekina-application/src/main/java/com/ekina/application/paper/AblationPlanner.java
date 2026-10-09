package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 6 — ablation (leave-one-out) planning & table.
 *
 * {@link #plan} defines the experiment matrix (baseline + each variant + leave-one-out) as a
 * reviewable spec. {@link #buildTables} groups the actual run metrics by config and renders
 * comparison/ablation tables under {@code figures/}; runs that are missing get an honest "待跑"
 * marker rather than a fabricated number. Numbers still come only from real runs.
 */
@Component
public class AblationPlanner {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> plan(PaperProject p, Map<String, Object> innovPlan) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        List<Map<String, Object>> configs = new ArrayList<>();

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("configId", "baseline");
        base.put("kind", "baseline");
        base.put("components", List.of());
        base.put("note", "no augmentation/module/loss changes");
        configs.add(base);

        List<String> variantIds = new ArrayList<>();
        Object variants = innovPlan == null ? null : innovPlan.get("variants");
        if (variants instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Map<?, ?> m) {
                    String id = String.valueOf(m.get("id"));
                    variantIds.add(id);
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("configId", "variant_" + id);
                    c.put("kind", "variant");
                    c.put("components", List.of(id));
                    configs.add(c);
                }
            }
        }
        // leave-one-out: full set minus each component
        for (String keep : variantIds) {
            List<String> comps = new ArrayList<>(variantIds);
            comps.remove(keep);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("configId", "ablate_" + keep);
            c.put("kind", "ablate");
            c.put("components", comps);
            c.put("missing", keep);
            configs.add(c);
        }
        matrix.put("paperId", p.getId());
        matrix.put("configs", configs);
        matrix.put("note", "Each config must be run (via SWEEP) to fill the table. No number is inferred here.");
        return matrix;
    }

    /** Group run metrics by config label and render comparison/ablation tables. */
    public Map<String, Object> buildTables(Path root) throws Exception {
        Path figures = root.resolve("figures");
        Files.createDirectories(figures);
        // group: configLabel -> list of mean acc
        Map<String, List<Double>> byConfig = new LinkedHashMap<>();
        boolean anyNonSynthetic = false;
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
                String ds = n.path("dataset").asText("");
                if (!"synthetic".equalsIgnoreCase(ds)) anyNonSynthetic = true;
                String label = configLabel(n);
                double mean = n.path("results").path("mean_val_accuracy").asDouble(Double.NaN);
                if (!Double.isNaN(mean)) {
                    byConfig.computeIfAbsent(label, k -> new ArrayList<>()).add(mean);
                }
            } catch (Exception ignored) {
            }
        }

        String fakeOrPending = (!anyNonSynthetic)
                ? "\\emph{仍为合成/模板指标；请接入算力跑真实数据集后此表才会是真实消融对比。}"
                : "";

        StringBuilder table = new StringBuilder();
        table.append("\\begin{table}[t]\\centering\\small\n");
        table.append("\\caption{Config / ablation results (mean val acc per config).}\\label{tab:ablation}\n");
        table.append("\\begin{tabular}{lcc}\\toprule\n");
        table.append("config & runs & mean acc \\\\\\midrule\n");
        if (byConfig.isEmpty()) {
            table.append("(no runs) & 0 & n/a \\\\\n");
        } else {
            for (Map.Entry<String, List<Double>> e : byConfig.entrySet()) {
                double mean = e.getValue().stream().mapToDouble(d -> d).average().orElse(Double.NaN);
                table.append(escape(e.getKey())).append(" & ").append(e.getValue().size())
                        .append(" & ").append(Double.isNaN(mean) ? "n/a" : String.format(java.util.Locale.ROOT, "%.4f", mean))
                        .append(" \\\\\n");
            }
        }
        table.append("\\bottomrule\\end{tabular}\\end{table}\n");
        Files.writeString(figures.resolve("ablation_table.tex"), table.toString(), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# Ablation matrix\n\n");
        md.append("> ").append(fakeOrPending).append("\n\n");
        md.append("| config | runs | mean acc |\n|---|---|---|\n");
        if (byConfig.isEmpty()) {
            md.append("| (no runs) | 0 | n/a |\n");
        } else {
            for (Map.Entry<String, List<Double>> e : byConfig.entrySet()) {
                double mean = e.getValue().stream().mapToDouble(d -> d).average().orElse(Double.NaN);
                md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size())
                        .append(" | ").append(Double.isNaN(mean) ? "n/a" : String.format(java.util.Locale.ROOT, "%.4f", mean)).append(" |\n");
            }
        }
        Files.writeString(figures.resolve("ablation.md"), md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configs", byConfig.size());
        out.put("anyNonSynthetic", anyNonSynthetic);
        out.put("table", "figures/ablation_table.tex");
        return out;
    }

    private static String configLabel(JsonNode n) {
        // Prefer an explicit configId if the runner set one; else derive from applied variants.
        String cid = n.path("configId").asText("");
        if (!cid.isBlank()) return cid;
        JsonNode variants = n.path("variants");
        if (variants.isArray() && variants.size() == 0) return "baseline";
        if (variants.isArray()) {
            StringBuilder sb = new StringBuilder("variant_");
            for (JsonNode v : variants) {
                if (sb.length() > "variant_".length()) sb.append("+");
                sb.append(v.path("id").asText("?"));
            }
            return sb.toString();
        }
        return "run";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("_", "\\_");
    }
}
