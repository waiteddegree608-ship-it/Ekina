package com.ekina.application.paper;

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
 * Builds a paper outline from reading notes + innov plan + repro inventory (structured, not free-hallucinated prose).
 */
@Service
public class OutlineBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public Map<String, Object> build(Path paperRoot, PaperProject project) throws Exception {
        Path notesDir = paperRoot.resolve("notes");
        Files.createDirectories(notesDir);

        Map<String, Object> outline = new LinkedHashMap<>();
        outline.put("paperId", project.getId());
        outline.put("mode", project.getMode() == null ? "RESEARCH" : project.getMode());
        outline.put("title", project.getTitleWorking());
        outline.put("domain", project.getDomain());
        outline.put("venueTier", project.getVenueTier());

        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(section("abstract", "Abstract",
                List.of("Problem", "Method one-liner", "Main empirical finding (only if metrics exist)")));
        sections.add(section("intro", "Introduction",
                List.of("Motivation", "Gap vs prior work", "Contributions (incremental, honest)")));
        sections.add(section("related", "Related Work",
                List.of("Organize by theme from lit/catalog.json", "Cite only verified bib keys")));
        sections.add(section("method", "Method",
                List.of("Baseline / reproduced seed", "Innovation variants from innov.json",
                        "Training setup")));
        sections.add(section("experiments", "Experiments",
                List.of("Datasets", "Metrics", "Ablations", "Limitations")));
        sections.add(section("conclusion", "Conclusion",
                List.of("Summary", "Future work")));
        outline.put("sections", sections);

        // Pull reading keywords
        Path readings = paperRoot.resolve("lit").resolve("readings");
        List<String> datasets = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        if (Files.isDirectory(readings)) {
            try (var walk = Files.walk(readings, 2)) {
                walk.filter(p -> p.getFileName().toString().equals("reading.json")).forEach(p -> {
                    try {
                        Map<String, Object> n = mapper.readValue(Files.readString(p), Map.class);
                        Object ds = n.get("datasetsMentioned");
                        if (ds instanceof List<?> list) {
                            for (Object x : list) if (!datasets.contains(String.valueOf(x))) datasets.add(String.valueOf(x));
                        }
                        Object mk = n.get("methodKeywords");
                        if (mk instanceof List<?> list) {
                            for (Object x : list) if (!methods.contains(String.valueOf(x))) methods.add(String.valueOf(x));
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        outline.put("datasetsFromReading", datasets);
        outline.put("methodsFromReading", methods);

        Map<String, Object> innov = readJson(paperRoot.resolve("innov.json"));
        outline.put("innovation", innov.get("variants"));
        outline.put("hypothesis", innov.get("hypothesis"));

        Map<String, Object> repro = readJson(paperRoot.resolve("repro").resolve("repro_report.json"));
        outline.put("reproStatus", repro.get("status"));
        outline.put("upstreamRepo", repro.get("repo"));

        StringBuilder md = new StringBuilder();
        md.append("# Outline — ").append(project.getTitleWorking()).append("\n\n");
        md.append("- mode: `").append(outline.get("mode")).append("`\n");
        md.append("- domain: ").append(project.getDomain()).append("\n");
        if (outline.get("upstreamRepo") != null) {
            md.append("- upstream: `").append(outline.get("upstreamRepo")).append("`\n");
        }
        md.append("\n## Section plan\n\n");
        for (Map<String, Object> s : sections) {
            md.append("### ").append(s.get("title")).append("\n");
            for (Object b : (List<?>) s.get("bullets")) {
                md.append("- ").append(b).append("\n");
            }
            md.append("\n");
        }
        md.append("## Datasets (from reading)\n\n");
        for (String d : datasets) md.append("- ").append(d).append("\n");
        md.append("\n## Method keywords (from reading)\n\n");
        for (String d : methods) md.append("- ").append(d).append("\n");
        md.append("\n## Innovation variants\n\n");
        Object variants = innov.get("variants");
        if (variants instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Map<?, ?> m) {
                    md.append("- **").append(m.get("id")).append("**: ").append(m.get("summary")).append("\n");
                }
            }
        }
        md.append("\n> Writing must not invent citations or metrics beyond verified artifacts.\n");

        Files.writeString(notesDir.resolve("outline.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(notesDir.resolve("outline.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(outline), StandardCharsets.UTF_8);
        outline.put("outlineMd", "notes/outline.md");
        return outline;
    }

    private static Map<String, Object> section(String id, String title, List<String> bullets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("bullets", bullets);
        return m;
    }

    private Map<String, Object> readJson(Path p) {
        try {
            if (!Files.isRegularFile(p)) return Map.of();
            return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
