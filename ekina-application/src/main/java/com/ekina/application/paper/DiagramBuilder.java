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
 * Architecture / pipeline framework diagrams (Mermaid + TikZ) and matplotlib curve scripts.
 */
@Service
public class DiagramBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> build(Path paperRoot, PaperProject project) throws Exception {
        Path figures = paperRoot.resolve("figures");
        Files.createDirectories(figures);
        Map<String, Object> art = new LinkedHashMap<>();

        Map<String, Object> innov = readMap(paperRoot.resolve("innov.json"));
        Map<String, Object> repro = readMap(paperRoot.resolve("repro").resolve("repro_report.json"));

        String mermaid = buildMermaid(project, innov, repro);
        Files.writeString(figures.resolve("architecture.mmd"), mermaid, StandardCharsets.UTF_8);

        String tikz = buildTikz(project, innov);
        Files.writeString(figures.resolve("architecture.tex"), tikz, StandardCharsets.UTF_8);

        Path texFig = paperRoot.resolve("tex").resolve("figures");
        Files.createDirectories(texFig);
        Files.writeString(texFig.resolve("architecture.tex"), tikz, StandardCharsets.UTF_8);
        Files.writeString(texFig.resolve("architecture.mmd"), mermaid, StandardCharsets.UTF_8);

        // matplotlib script from metrics if present
        Map<String, Object> plot = writePlotScript(paperRoot, figures, texFig);
        art.put("architectureMermaid", "figures/architecture.mmd");
        art.put("architectureTex", "figures/architecture.tex");
        art.put("plot", plot);
        art.put("status", "ok");
        return art;
    }

    @SuppressWarnings("unchecked")
    private String buildMermaid(PaperProject project, Map<String, Object> innov, Map<String, Object> repro) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");
        sb.append("  LIT[Literature] --> READ[Reading notes]\n");
        sb.append("  READ --> REPRO[Upstream repro]\n");
        sb.append("  REPRO --> INNOV[Innovation variants]\n");
        sb.append("  INNOV --> TRAIN[Train / Sweep]\n");
        sb.append("  TRAIN --> FIG[Figures]\n");
        sb.append("  FIG --> TEX[LaTeX paper]\n");
        if (repro.get("repo") != null) {
            sb.append("  REPRO -.->|").append(escape(String.valueOf(repro.get("repo")))).append("| GH[GitHub]\n");
        }
        Object variants = innov.get("variants");
        if (variants instanceof List<?> list) {
            int i = 0;
            for (Object v : list) {
                if (v instanceof Map<?, ?> m) {
                    sb.append("  INNOV --> V").append(i).append("[").append(escape(String.valueOf(m.get("id")))).append("]\n");
                    i++;
                }
            }
        }
        sb.append("  %% paper ").append(escape(project.getId())).append(" mode=")
                .append(escape(String.valueOf(project.getMode()))).append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildTikz(PaperProject project, Map<String, Object> innov) {
        StringBuilder sb = new StringBuilder();
        sb.append("% Auto-generated framework diagram — compile inside main.tex or standalone\n");
        sb.append("\\begin{tikzpicture}[node distance=1.1cm, every node/.style={font=\\small}]\n");
        sb.append("  \\node[draw,rounded corners] (lit) {Lit};\n");
        sb.append("  \\node[draw,rounded corners, right=of lit] (repro) {Repro};\n");
        sb.append("  \\node[draw,rounded corners, right=of repro] (inn) {Innov};\n");
        sb.append("  \\node[draw,rounded corners, right=of inn] (sw) {Sweep};\n");
        sb.append("  \\node[draw,rounded corners, right=of sw] (tex) {TeX};\n");
        sb.append("  \\draw[->] (lit) -- (repro);\n");
        sb.append("  \\draw[->] (repro) -- (inn);\n");
        sb.append("  \\draw[->] (inn) -- (sw);\n");
        sb.append("  \\draw[->] (sw) -- (tex);\n");
        Object variants = innov.get("variants");
        if (variants instanceof List<?> list && !list.isEmpty()) {
            sb.append("  \\node[below=0.8cm of inn, align=left] {");
            int i = 0;
            for (Object v : list) {
                if (v instanceof Map<?, ?> m) {
                    if (i++ > 0) sb.append("\\\\\n    ");
                    sb.append("\\texttt{").append(escapeLatex(String.valueOf(m.get("id")))).append("}");
                }
            }
            sb.append("};\n");
        }
        sb.append("\\end{tikzpicture}\n");
        return sb.toString();
    }

    private Map<String, Object> writePlotScript(Path paperRoot, Path figures, Path texFig) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        Path metrics = paperRoot.resolve("runs").resolve("latest_metrics.json");
        if (!Files.isRegularFile(metrics)) {
            out.put("status", "skipped_no_metrics");
            return out;
        }
        JsonNode root = mapper.readTree(Files.readString(metrics, StandardCharsets.UTF_8));
        List<String> xs = new ArrayList<>();
        List<String> ys = new ArrayList<>();
        for (JsonNode row : root.path("results").path("per_seed")) {
            xs.add(row.path("seed").asText("0"));
            ys.add(row.path("val_accuracy").asText("0"));
        }
        String py = """
                #!/usr/bin/env python3
                import json, pathlib
                try:
                    import matplotlib
                    matplotlib.use("Agg")
                    import matplotlib.pyplot as plt
                except Exception as e:
                    open("figures/plot_error.txt","w").write(str(e))
                    raise SystemExit(0)
                seeds = %s
                accs = %s
                fig, ax = plt.subplots(figsize=(4.5, 3.0))
                ax.bar([str(s) for s in seeds], [float(a) for a in accs], color="#3d5a5b")
                ax.set_xlabel("seed")
                ax.set_ylabel("val_accuracy")
                ax.set_title("Per-seed accuracy (this run)")
                fig.tight_layout()
                pathlib.Path("figures").mkdir(exist_ok=True)
                fig.savefig("figures/acc_bar.png", dpi=160)
                fig.savefig("tex/figures/acc_bar.png", dpi=160)
                print("WROTE figures/acc_bar.png")
                """.formatted(xs.toString(), ys.toString());
        Path script = figures.resolve("plot_acc.py");
        Files.writeString(script, py, StandardCharsets.UTF_8);
        out.put("script", "figures/plot_acc.py");

        // try run from paper root
        try {
            ProcessBuilder pb = new ProcessBuilder("python", script.toAbsolutePath().toString());
            pb.directory(paperRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            out.put("ran", ok && p.exitValue() == 0);
            out.put("exitCode", ok ? p.exitValue() : -1);
            out.put("png", Files.isRegularFile(figures.resolve("acc_bar.png")));
        } catch (Exception e) {
            out.put("ran", false);
            out.put("error", e.getMessage());
        }
        out.put("status", "ok");
        return out;
    }

    private Map<String, Object> readMap(Path p) {
        try {
            if (!Files.isRegularFile(p)) return Map.of();
            return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("[", "(").replace("]", ")");
    }

    private static String escapeLatex(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\textbackslash{}")
                .replace("_", "\\_")
                .replace("%", "\\%")
                .replace("&", "\\&");
    }
}
