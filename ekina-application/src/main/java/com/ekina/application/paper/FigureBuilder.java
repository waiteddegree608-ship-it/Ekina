package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FigureBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> buildFromRuns(Path paperRoot) throws Exception {
        Path figures = paperRoot.resolve("figures");
        Files.createDirectories(figures);
        Path runs = paperRoot.resolve("runs");
        List<Path> metricFiles = new ArrayList<>();
        if (Files.isDirectory(runs)) {
            try (var walk = Files.walk(runs, 3)) {
                walk.filter(p -> p.getFileName().toString().equals("metrics.json")).forEach(metricFiles::add);
            }
        }
        Map<String, Object> art = new LinkedHashMap<>();
        if (metricFiles.isEmpty()) {
            Files.writeString(figures.resolve("README.md"),
                    "# Figures\n\nNo metrics.json yet.\n", StandardCharsets.UTF_8);
            art.put("status", "skipped_no_metrics");
            art.put("tables", 0);
            return art;
        }
        Path latest = metricFiles.get(metricFiles.size() - 1);
        JsonNode root = mapper.readTree(Files.readString(latest, StandardCharsets.UTF_8));

        StringBuilder md = new StringBuilder();
        md.append("# Experiment results\n\n");
        md.append("- source: `").append(rel(paperRoot, latest)).append("`\n");
        md.append("- status: ").append(root.path("status").asText("")).append("\n");
        md.append("- gpu: ").append(root.path("gpu").asText("n/a")).append("\n");
        md.append("- mean_val_accuracy: **")
                .append(root.path("results").path("mean_val_accuracy").asText("n/a"))
                .append("**\n\n");

        md.append("## Per-seed\n\n| seed | val_accuracy | data | device | wallSec | lora_rank | aug | focal |\n");
        md.append("|------|--------------|------|--------|---------|-----------|-----|-------|\n");
        for (JsonNode row : root.path("results").path("per_seed")) {
            md.append("| ").append(row.path("seed").asText())
                    .append(" | ").append(row.path("val_accuracy").asText())
                    .append(" | ").append(row.path("data").asText())
                    .append(" | ").append(row.path("device").asText())
                    .append(" | ").append(row.path("wallSec").asText())
                    .append(" | ").append(row.path("lora_rank").asText())
                    .append(" | ").append(row.path("aug_noise").asText())
                    .append(" | ").append(row.path("focal").asText())
                    .append(" |\n");
        }

        StringBuilder texTable = new StringBuilder();
        texTable.append("\\begin{table}[t]\\centering\\small\n");
        texTable.append("\\caption{Incremental ablation results (this run).}\\label{tab:results}\n");
        texTable.append("\\begin{tabular}{rcccc}\\toprule\n");
        texTable.append("seed & val acc. & LoRA $r$ & aug & focal \\\\\\midrule\n");
        for (JsonNode row : root.path("results").path("per_seed")) {
            texTable.append(row.path("seed").asText()).append(" & ")
                    .append(row.path("val_accuracy").asText()).append(" & ")
                    .append(row.path("lora_rank").asText()).append(" & ")
                    .append(row.path("aug_noise").asText()).append(" & ")
                    .append(row.path("focal").asText()).append(" \\\\\n");
        }
        String mean = root.path("results").path("mean_val_accuracy").asText("");
        if (!mean.isBlank() && !"null".equals(mean)) {
            texTable.append("\\midrule\nmean & ").append(mean).append(" & & & \\\\\n");
        }
        texTable.append("\\bottomrule\\end{tabular}\\end{table}\n");

        Files.writeString(figures.resolve("tables.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(figures.resolve("results_table.tex"), texTable.toString(), StandardCharsets.UTF_8);

        Path texFig = paperRoot.resolve("tex").resolve("figures");
        Files.createDirectories(texFig);
        Files.copy(figures.resolve("tables.md"), texFig.resolve("tables.md"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(figures.resolve("results_table.tex"), texFig.resolve("results_table.tex"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        art.put("status", "ok");
        art.put("tables", 1);
        art.put("mean_val_accuracy", mean);
        art.put("source", latest.toAbsolutePath().toString());
        return art;
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.toString();
        }
    }
}
