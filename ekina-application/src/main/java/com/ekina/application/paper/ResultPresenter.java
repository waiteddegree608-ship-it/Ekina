package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 9 — present the whole result set to the owner.
 *
 * Aggregates every stage artifact (literature, venue, baseline, innovation, runs/analysis, gate,
 * LaTeX) into a single human-readable {@code RESULT.md} plus a machine summary map, and returns the
 * paths. Honest about anything still missing (e.g. no real metrics yet).
 */
@Service
public class ResultPresenter {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> present(PaperProject p, Path root) throws Exception {
        Map<String, Object> analysis = readJson(root.resolve("notes").resolve("analysis.json"));
        Map<String, Object> gate = readJson(root.resolve("gate_report.json"));
        Map<String, Object> innov = readJson(root.resolve("innov.json"));
        Map<String, Object> repro = readJson(root.resolve("repro").resolve("repro_report.json"));
        String venueName = venueName(p);

        StringBuilder md = new StringBuilder();
        md.append("# 结果包 — ").append(p.getTitleWorking() == null ? p.getId() : p.getTitleWorking()).append("\n\n");
        md.append("- id: `").append(p.getId()).append("`\n");
        md.append("- domain: ").append(blank(p.getDomain())).append("  ·  mode: `").append(blank(p.getMode())).append("`  ·  tier: `").append(blank(p.getVenueTier())).append("`\n");
        md.append("- 拟定目标期刊: ").append(venueName).append("\n");
        md.append("- 状态: `").append(blank(p.getStatus())).append("`  ·  阶段: `").append(blank(p.getStage())).append("`\n\n");

        md.append("## 1. 文献\n");
        if (Files.isRegularFile(root.resolve("lit").resolve("notes.md"))) {
            md.append("见 `lit/notes.md`（含顶会/顶刊分级）。").append("\n");
        } else {
            md.append("（无文献笔记）\n");
        }

        md.append("\n## 2. Baseline 与创新点\n");
        Object reproStatus = repro.get("status");
        md.append("- baseline: ").append(blank(reproStatus)).append("\n");
        Object variants = innov.get("variants");
        if (variants instanceof List<?> list && !list.isEmpty()) {
            md.append("- 创新变体:\n");
            for (Object v : list) {
                if (v instanceof Map<?, ?> m) md.append("  - `").append(m.get("id")).append("`: ").append(m.get("summary")).append("\n");
            }
        } else {
            md.append("- 创新变体: （无）\n");
        }

        md.append("\n## 3. 实验与分析\n");
        if (analysis != null && analysis.get("runCount") instanceof Number n && n.intValue() > 0) {
            md.append("见 `notes/analysis.md`。").append("\n");
            md.append("- 结论: ").append(blank(analysis.get("conclusion"))).append("\n");
        } else {
            md.append("暂无可分析的真实 run；请授权算力并跑通 SWEEP（`needs_compute`）。\n");
        }

        md.append("\n## 4. 门禁\n");
        md.append("- pass: `").append(gate.get("pass")).append("`  ·  score: ").append(gate.get("score"))
                .append("/").append(gate.get("maxScore")).append("  ·  honesty: `").append(blank(gate.get("honestyLevel"))).append("`\n");

        md.append("\n## 5. 产物\n");
        for (String part : List.of("lit/catalog.json", "lit/notes.md", "notes/outline.md", "notes/analysis.md",
                "figures/results_table.tex", "figures/architecture.*", "tex/main.tex", "tex/refs.bib",
                "outbox/<paperId>_latex.zip", "repro/repro_report.json", "repro/BASELINE.md", "RESULT.md")) {
            md.append("- `").append(part).append("`\n");
        }

        md.append("\n## 6. 复现\n");
        md.append("- 编译 LaTeX: `pdflatex main && bibtex main && pdflatex main && pdflatex main`（或在 Overleaf）\n");
        md.append("- 训练脚本: `code/train_experiment.py`（需算力授权后才会产出真实 metrics）\n");

        Files.writeString(root.resolve("RESULT.md"), md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultMd", "RESULT.md");
        out.put("absResult", root.resolve("RESULT.md").toAbsolutePath().toString());
        out.put("venue", venueName);
        out.put("status", p.getStatus());
        out.put("stage", p.getStage() == null ? "" : p.getStage().name());
        out.put("conclusion", analysis == null ? null : analysis.get("conclusion"));
        return out;
    }

    private static String venueName(PaperProject p) {
        if (p.getMeta() != null && p.getMeta().get("venue") instanceof Map<?, ?> m) {
            Object name = m.get("name");
            Object cls = m.get("latexClass");
            if (name != null) return String.valueOf(name) + " (" + blank(cls) + ")";
        }
        return "（未匹配到期刊，默认 article 模板）";
    }

    private Map<String, Object> readJson(Path path) {
        try {
            if (!Files.isRegularFile(path)) return Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String blank(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
