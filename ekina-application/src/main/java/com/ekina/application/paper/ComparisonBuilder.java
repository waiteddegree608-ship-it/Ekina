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
 * Step 5 — builds an honest "our measured result vs baseline vs top-venue (claimed)" comparison
 * table. It NEVER equates a literature-claimed number with a measured one: each row is explicitly
 * labelled Type = measured | claimed. Real numbers come only from actual runs; anything not yet run
 * is shown as pending rather than invented.
 */
@Component
public class ComparisonBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> build(Path root) throws Exception {
        Path figures = root.resolve("figures");
        Files.createDirectories(figures);

        List<Map<String, String>> rows = new ArrayList<>();

        // measured rows from real runs
        List<Path> metrics = new ArrayList<>();
        Path runsDir = root.resolve("runs");
        if (Files.isDirectory(runsDir)) {
            try (var walk = Files.walk(runsDir, 3)) {
                walk.filter(f -> f.getFileName().toString().equals("metrics.json")).forEach(metrics::add);
            }
        }
        boolean anyNonSynthetic = false;
        if (metrics.isEmpty()) {
            rows.add(row("our baseline/variants", "n/a", "measured", "no runs yet"));
        } else {
            for (Path mf : metrics) {
                try {
                    JsonNode n = mapper.readTree(Files.readString(mf, StandardCharsets.UTF_8));
                    String ds = n.path("dataset").asText("");
                    boolean synth = "synthetic".equalsIgnoreCase(ds);
                    if (!synth) anyNonSynthetic = true;
                    double mean = n.path("results").path("mean_val_accuracy").asDouble(Double.NaN);
                    String label = label(n);
                    rows.add(row("ours: " + label, Double.isNaN(mean) ? "n/a" : String.format(java.util.Locale.ROOT, "%.4f", mean),
                            synth ? "measured(template)" : "measured", ds));
                } catch (Exception ignored) {
                }
            }
        }

        // claimed rows from lit (Semantic Scholar / literature)
        Path claimed = root.resolve("repro").resolve("claimed_metrics.json");
        boolean claimedFound = Files.isRegularFile(claimed) && Files.size(claimed) > 2;
        if (claimedFound) {
            try {
                JsonNode c = mapper.readTree(Files.readString(claimed, StandardCharsets.UTF_8));
                int shown = 0;
                for (JsonNode hit : c.path("hits")) {
                    if (shown >= 6) break;
                    rows.add(row("lit: " + hit.path("source").asText(""),
                            hit.path("value").asText(""),
                            "claimed", hit.path("metric").asText("")));
                    shown++;
                }
            } catch (Exception ignored) {
            }
        }

        StringBuilder tex = new StringBuilder();
        tex.append("\\begin{table}[t]\\centering\\small\n");
        tex.append("\\caption{Comparison: our measured runs vs top-venue / baseline (claimed).}\\label{tab:compare}\n");
        tex.append("\\begin{tabular}{lccc}\\toprule\n");
        tex.append("method & value & type & dataset \\\\\\midrule\n");
        for (Map<String, String> r : rows) {
            tex.append(esc(r.get("method"))).append(" & ").append(esc(r.get("value"))).append(" & ")
                    .append(esc(r.get("type"))).append(" & ").append(esc(r.get("dataset"))).append(" \\\\\n");
        }
        tex.append("\\bottomrule\\end{tabular}\\end{table}\n");
        if (!anyNonSynthetic) {
            tex.append("\\emph{注: 表中 'measured' 若为 template/synthetic 只是链路自测, 非真实实验; 接入算力跑真实数据集后重做。}\n");
        }
        Files.writeString(figures.resolve("comparison_table.tex"), tex.toString(), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# Comparison (measured vs claimed)\n\n");
        md.append("| method | value | type | dataset |\n|---|---|---|---|\n");
        for (Map<String, String> r : rows) {
            md.append("| ").append(r.get("method")).append(" | ").append(r.get("value")).append(" | ")
                    .append(r.get("type")).append(" | ").append(r.get("dataset")).append(" |\n");
        }
        Files.writeString(figures.resolve("comparison.md"), md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.size());
        out.put("anyNonSynthetic", anyNonSynthetic);
        out.put("claimedFound", claimedFound);
        out.put("table", "figures/comparison_table.tex");
        return out;
    }

    private static String label(JsonNode n) {
        String cid = n.path("configId").asText("");
        if (!cid.isBlank()) return cid;
        return "run_" + n.path("runId").asText("?");
    }

    private static Map<String, String> row(String method, String value, String type, String dataset) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", method);
        m.put("value", value);
        m.put("type", type);
        m.put("dataset", dataset == null ? "" : dataset);
        return m;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_").replace("%", "\\%").replace("&", "\\&");
    }
}
