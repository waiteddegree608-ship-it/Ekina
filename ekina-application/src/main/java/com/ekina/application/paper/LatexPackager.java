package com.ekina.application.paper;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Emits a minimal IMRAD LaTeX project from verified literature catalog only.
 */
@Service
public class LatexPackager {

    @org.springframework.beans.factory.annotation.Autowired
    private JournalFormatService journalFormatService;

    public Map<String, Object> generateTex(Path paperRoot, PaperProject project, List<LitPaper> catalog)
            throws Exception {
        Path texDir = paperRoot.resolve("tex");
        Files.createDirectories(texDir);
        Files.createDirectories(texDir.resolve("figures"));
        String kitId = resolveKitId(project);
        if (kitId == null || kitId.isBlank()) {
            kitId = "ieee-conf";
        }
        if (journalFormatService != null) {
            journalFormatService.apply(kitId, texDir, project.getTitleWorking());
        }

        String title = project.getTitleWorking() == null || project.getTitleWorking().isBlank()
                ? "A Study on " + nullTo(project.getDomain(), "Machine Learning")
                : project.getTitleWorking();

        List<LitPaper> citeable = new ArrayList<>();
        for (LitPaper p : catalog) {
            if (p != null && p.hasStableId()) citeable.add(p);
        }
        if (citeable.isEmpty()) {
            throw new IllegalStateException("No citeable papers with DOI/arXiv — refuse to write");
        }

        String bib = buildBib(citeable);
        Files.writeString(texDir.resolve("refs.bib"), bib, StandardCharsets.UTF_8);

        StringBuilder cites = new StringBuilder();
        int n = Math.min(8, citeable.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) cites.append(",");
            cites.append(bibKey(citeable.get(i)));
        }

        String related = buildRelatedWork(citeable);
        String methodExtra = buildMethodFromInnov(paperRoot);
        String experiments = buildExperiments(paperRoot);
        String framework = buildFramework(paperRoot);
        String outlineNote = buildOutlineNote(paperRoot);
        String mode = project.getMode() == null ? "RESEARCH" : project.getMode();
        Path mainTex = texDir.resolve("main.tex");
        String tex;
        if (Files.isRegularFile(mainTex) && kitId != null) {
            tex = Files.readString(mainTex, StandardCharsets.UTF_8)
                    .replace("{{TITLE}}", escapeLatex(title))
                    .replace("{{ABSTRACT}}", escapeLatex("Automated draft on " + nullTo(project.getDomain(), "ML")
                            + ". Pipeline mode " + mode + ". Claims must be backed by metrics.json."))
                    .replace("{{INTRO}}", "We study " + escapeLatex(nullTo(project.getDomain(), "ML"))
                            + " with references drawn only from live scholarly APIs\\cite{" + cites + "}.")
                    .replace("{{RELATED}}", related)
                    .replace("{{METHOD}}", methodExtra + "\n\n" + framework)
                    .replace("{{EXPERIMENTS}}", experiments)
                    .replace("{{CONCLUSION}}", "Limits first. Paper id: " + escapeLatex(project.getId())
                            + ".\n\n" + outlineNote);
        } else {
            String docClass = resolveDocClass(project);
            tex = TEMPLATE
                    .replace("{{TITLE}}", escapeLatex(title))
                    .replace("{{DOMAIN}}", escapeLatex(nullTo(project.getDomain(), "ML")))
                    .replace("{{CITES}}", cites.toString())
                    .replace("{{RELATED}}", related)
                    .replace("{{METHOD_EXTRA}}", methodExtra)
                    .replace("{{EXPERIMENTS}}", experiments)
                    .replace("{{FRAMEWORK}}", framework)
                    .replace("{{OUTLINE}}", outlineNote)
                    .replace("{{MODE}}", escapeLatex(mode))
                    .replace("{{PAPER_ID}}", escapeLatex(project.getId()))
                    .replace("\\documentclass[11pt]{article}", docClass);
        }

        Files.writeString(texDir.resolve("main.tex"), tex, StandardCharsets.UTF_8);
        Files.writeString(texDir.resolve("README.md"),
                "# LaTeX project for " + project.getId() + "\n\n"
                        + "Venue kit: " + (kitId == null ? "generic article (avoid)" : kitId) + "\n"
                        + "Compile with: `pdflatex main && bibtex main && pdflatex main && pdflatex main`\n"
                        + "Or open in Overleaf. All refs.bib entries must pass CitationVerifier.\n",
                StandardCharsets.UTF_8);

        // Placeholder figure note (FIG stage later replaces)
        Files.writeString(texDir.resolve("figures").resolve("README.txt"),
                "Put experiment figures here. Gate will warn if empty.\n", StandardCharsets.UTF_8);

        Map<String, Object> art = new LinkedHashMap<>();
        art.put("status", "ok");
        art.put("tex", "tex/main.tex");
        art.put("bib", "tex/refs.bib");
        art.put("citeCount", citeable.size());
        art.put("title", title);
        return art;
    }

    public Map<String, Object> packZip(Path paperRoot, PaperProject project) throws Exception {
        Path texDir = paperRoot.resolve("tex");
        Path outbox = paperRoot.resolve("outbox");
        Files.createDirectories(outbox);
        String name = project.getId() + "_latex.zip";
        Path zipPath = outbox.resolve(name);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            Files.walkFileTree(texDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entry = "paper/" + texDir.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entry));
                    zos.write(Files.readAllBytes(file));
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.write(zipPath, bos.toByteArray());

        Map<String, Object> compile = tryCompile(texDir);

        Map<String, Object> art = new LinkedHashMap<>();
        art.put("status", "ok");
        art.put("zip", zipPath.toAbsolutePath().toString());
        art.put("bytes", Files.size(zipPath));
        art.put("compile", compile);
        return art;
    }

    /** Best-effort local compile if pdflatex/tectonic exists; never invent PDF. */
    public Map<String, Object> tryCompile(Path texDir) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("attempted", false);
        r.put("ok", false);
        String[] cmds = {"pdflatex", "tectonic"};
        for (String cmd : cmds) {
            try {
                ProcessBuilder pb;
                if ("tectonic".equals(cmd)) {
                    pb = new ProcessBuilder(cmd, "main.tex");
                } else {
                    pb = new ProcessBuilder(cmd, "-interaction=nonstopmode", "main.tex");
                }
                pb.directory(texDir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean finished = p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    continue;
                }
                r.put("attempted", true);
                r.put("engine", cmd);
                r.put("exitCode", p.exitValue());
                Path pdf = texDir.resolve("main.pdf");
                r.put("ok", p.exitValue() == 0 && Files.isRegularFile(pdf));
                r.put("pdf", Files.isRegularFile(pdf) ? pdf.toAbsolutePath().toString() : null);
                return r;
            } catch (Exception ignored) {
            }
        }
        r.put("note", "No pdflatex/tectonic on PATH — zip is still valid for Overleaf/local TeX");
        return r;
    }

    private String buildBib(List<LitPaper> papers) {
        StringBuilder sb = new StringBuilder();
        for (LitPaper p : papers) {
            String key = bibKey(p);
            sb.append("@article{").append(key).append(",\n");
            sb.append("  title={").append(escapeBib(p.getTitle())).append("},\n");
            if (p.getAuthors() != null && !p.getAuthors().isEmpty()) {
                sb.append("  author={").append(escapeBib(String.join(" and ", p.getAuthors()))).append("},\n");
            }
            if (p.getYear() != null) sb.append("  year={").append(p.getYear()).append("},\n");
            if (p.getVenue() != null) sb.append("  journal={").append(escapeBib(p.getVenue())).append("},\n");
            if (p.getDoi() != null && !p.getDoi().isBlank()) {
                sb.append("  doi={").append(escapeBib(p.getDoi())).append("},\n");
                sb.append("  url={https://doi.org/").append(escapeBib(p.getDoi())).append("},\n");
            }
            if (p.getArxivId() != null && !p.getArxivId().isBlank()) {
                sb.append("  eprint={").append(escapeBib(p.getArxivId())).append("},\n");
                sb.append("  archivePrefix={arXiv},\n");
            }
            sb.append("}\n\n");
        }
        return sb.toString();
    }

    private String buildMethodFromInnov(Path paperRoot) {
        try {
            Path innov = paperRoot.resolve("innov.json");
            if (!Files.isRegularFile(innov)) {
                return "Incremental variants will be selected from the innovation menu (dataset / module / workload).";
            }
            String raw = Files.readString(innov, StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            StringBuilder sb = new StringBuilder();
            sb.append("We study \\emph{incremental} changes (dataset swap, module stitch, workload stacking):\n");
            sb.append("\\begin{itemize}\n");
            for (com.fasterxml.jackson.databind.JsonNode v : root.path("variants")) {
                sb.append("\\item \\textbf{")
                        .append(escapeLatex(v.path("id").asText()))
                        .append("} (")
                        .append(escapeLatex(v.path("kind").asText()))
                        .append("): ")
                        .append(escapeLatex(v.path("summary").asText()))
                        .append("\n");
            }
            sb.append("\\end{itemize}\n");
            String hyp = root.path("hypothesis").asText("");
            if (!hyp.isBlank()) {
                sb.append("\n").append(escapeLatex(hyp)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "See \\texttt{innov.json} in the project workspace.";
        }
    }

    private String buildExperiments(Path paperRoot) {
        try {
            Path texTable = paperRoot.resolve("figures").resolve("results_table.tex");
            Path metrics = paperRoot.resolve("runs").resolve("latest_metrics.json");
            if (Files.isRegularFile(texTable)) {
                String table = Files.readString(texTable, StandardCharsets.UTF_8);
                String note = Files.isRegularFile(metrics)
                        ? "Numbers below come from \\texttt{metrics.json} produced on the compute node."
                        : "";
                return note + "\n\n" + table + "\n";
            }
            if (Files.isRegularFile(metrics)) {
                String body = Files.readString(metrics, StandardCharsets.UTF_8);
                if (body.length() > 2500) body = body.substring(0, 2500) + "\n...";
                return "\\begin{verbatim}\n" + body + "\n\\end{verbatim}\n";
            }
            return "\\emph{No metrics.json yet.} Grant ssh\\_remote and re-run SWEEP before claiming numerical gains.";
        } catch (Exception e) {
            return "Experiment section pending.";
        }
    }

    private String buildFramework(Path paperRoot) {
        try {
            Path arch = paperRoot.resolve("figures").resolve("architecture.tex");
            if (Files.isRegularFile(arch)) {
                return "\\begin{figure}[t]\\centering\n"
                        + Files.readString(arch, StandardCharsets.UTF_8)
                        + "\\caption{Pipeline / innovation framework (auto-generated).}\\label{fig:framework}\n"
                        + "\\end{figure}\n";
            }
        } catch (Exception ignored) {
        }
        return "Framework diagram pending (\\texttt{figures/architecture.tex}).\n";
    }

    private String buildOutlineNote(Path paperRoot) {
        try {
            Path outline = paperRoot.resolve("notes").resolve("outline.md");
            if (!Files.isRegularFile(outline)) return "";
            String body = Files.readString(outline, StandardCharsets.UTF_8);
            if (body.length() > 1800) body = body.substring(0, 1800) + "\n...";
            return "\\begin{verbatim}\n" + body.replace("\\", "/") + "\n\\end{verbatim}\n";
        } catch (Exception e) {
            return "";
        }
    }

    private String buildRelatedWork(List<LitPaper> papers) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (LitPaper p : papers) {
            if (i++ >= 10) break;
            String abs = p.getAbstractText() == null ? "" : p.getAbstractText();
            if (abs.length() > 280) abs = abs.substring(0, 280) + "...";
            sb.append("\\paragraph{").append(escapeLatex(nullTo(p.getTitle(), "Untitled"))).append("}\n");
            sb.append("\\cite{").append(bibKey(p)).append("}");
            if (!abs.isBlank()) {
                sb.append(" ").append(escapeLatex(abs));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private static String resolveKitId(PaperProject project) {
        if (project.getMeta() == null) return null;
        Object venue = project.getMeta().get("venue");
        if (venue instanceof Map<?, ?> m) {
            Object kit = m.get("kitId");
            if (kit != null && !String.valueOf(kit).isBlank()) return String.valueOf(kit);
            Object id = m.get("id");
            if (id != null && !String.valueOf(id).isBlank()) return String.valueOf(id);
        }
        return null;
    }

    /** Map the chosen venue's LaTeX class to a concrete documentclass declaration. */
    private static String resolveDocClass(PaperProject project) {
        String cls = "";
        if (project.getMeta() != null) {
            Object venue = project.getMeta().get("venue");
            if (venue instanceof Map<?, ?> m) {
                Object lc = m.get("latexClass");
                cls = lc == null ? "" : String.valueOf(lc);
            }
        }
        return switch (cls) {
            case "IEEEtran" -> "\\documentclass[conference]{IEEEtran}";
            case "elsarticle" -> "\\documentclass[preprint,12pt]{elsarticle}";
            case "svjour3" -> "\\documentclass[twocolumn]{svjour3}";
            default -> "\\documentclass[11pt]{article}";
        };
    }

    static String bibKey(LitPaper p) {
        String base;
        if (p.getDoi() != null && !p.getDoi().isBlank()) {
            base = "doi_" + p.getDoi().replaceAll("[^A-Za-z0-9]", "_");
        } else {
            base = "arxiv_" + p.getArxivId().replaceAll("[^A-Za-z0-9]", "_");
        }
        if (base.length() > 40) base = base.substring(0, 40);
        return base.toLowerCase(Locale.ROOT);
    }

    private static String escapeLatex(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("$", "\\$")
                .replace("#", "\\#")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("~", "\\textasciitilde{}")
                .replace("^", "\\textasciicircum{}");
    }

    private static String escapeBib(String s) {
        if (s == null) return "";
        return s.replace("{", "").replace("}", "");
    }

    private static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static final String TEMPLATE = """
            \\documentclass[11pt]{article}
            \\usepackage[utf8]{inputenc}
            \\usepackage[T1]{fontenc}
            \\usepackage{graphicx}
            \\usepackage{hyperref}
            \\usepackage{booktabs}
            \\usepackage{amsmath}
            \\usepackage{tikz}
            \\usetikzlibrary{positioning}
            
            \\title{{{TITLE}}}
            \\author{Ekina Paper Pipeline\\\\(automated draft — verify before submission)}
            \\date{\\today}
            
            \\begin{document}
            \\maketitle
            
            \\begin{abstract}
            This automated draft surveys recent work on {{DOMAIN}} and outlines a reproducible experimental plan.
            Pipeline mode: \\texttt{{{MODE}}}.
            Empirical claims in later revisions MUST be backed by \\texttt{runs/*/metrics.json} artifacts.
            Paper id: {{PAPER_ID}}.
            \\end{abstract}
            
            \\section{Introduction}
            Automated scientific drafting requires grounding every citation in a verifiable identifier (DOI or arXiv).
            We study {{DOMAIN}} with references drawn only from live scholarly APIs\\cite{{CITES}}.
            
            \\section{Outline (machine checklist)}
            {{OUTLINE}}
            
            \\section{Related Work}
            {{RELATED}}
            
            \\section{Method}
            {{METHOD_EXTRA}}
            
            \\subsection{Framework}
            {{FRAMEWORK}}
            
            \\section{Experiments}
            {{EXPERIMENTS}}
            
            \\section{Conclusion}
            This package is a machine-checkable scaffold: literature is API-backed; bibliography must pass CitationVerifier;
            repro inventory (if any) lives under \\texttt{repro/}; a zip suitable for Overleaf is under \\texttt{outbox/}.
            
            \\bibliographystyle{plain}
            \\bibliography{refs}
            
            \\end{document}
            """;
}
