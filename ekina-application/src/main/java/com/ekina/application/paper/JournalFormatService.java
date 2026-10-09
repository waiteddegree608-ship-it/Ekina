package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Official-looking venue kits under templates/venues/{id}.
 * Copies the kit so a PDF is identifiable as NeurIPS / IEEE / Springer / etc.
 */
@Service
public class JournalFormatService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    public Path venuesRoot() {
        return Path.of(workspaceDir, "templates", "venues");
    }

    public List<Map<String, Object>> listVenues() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path root = venuesRoot();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                Path meta = dir.resolve("VENUE.json");
                if (!Files.isRegularFile(meta)) return;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = mapper.readValue(Files.readString(meta, StandardCharsets.UTF_8), Map.class);
                    row.put("dir", dir.toAbsolutePath().toString());
                    out.add(row);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    public Map<String, Object> find(String venueId) {
        if (venueId == null || venueId.isBlank()) return null;
        String q = venueId.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> v : listVenues()) {
            if (q.equals(String.valueOf(v.get("id")).toLowerCase(Locale.ROOT))) return v;
            if (q.equals(String.valueOf(v.get("name")).toLowerCase(Locale.ROOT))) return v;
            if (String.valueOf(v.get("fullName")).toLowerCase(Locale.ROOT).contains(q)) return v;
        }
        return null;
    }

    public Map<String, Object> apply(String venueId, Path destDir, String title) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> venue = find(venueId);
        if (venue == null) {
            out.put("ok", false);
            out.put("error", "未知 venue：" + venueId + "。先 listVenues。不要用裸 article。");
            return out;
        }
        try {
            Path src = Path.of(String.valueOf(venue.get("dir")));
            Files.createDirectories(destDir);
            Files.createDirectories(destDir.resolve("figures"));
            try (Stream<Path> files = Files.list(src)) {
                files.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        Files.copy(f, destDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            copySpringerCls(destDir, String.valueOf(venue.get("id")));
            Path main = destDir.resolve("main.tex");
            if (Files.isRegularFile(main)) {
                String tex = Files.readString(main, StandardCharsets.UTF_8);
                tex = fill(tex, title);
                Files.writeString(main, tex, StandardCharsets.UTF_8);
            }
            if (!Files.exists(destDir.resolve("refs.bib")) && !Files.exists(destDir.resolve("sn-bibliography.bib"))) {
                Files.writeString(destDir.resolve("refs.bib"), "% DOI/arXiv only\n", StandardCharsets.UTF_8);
            }
            Files.writeString(destDir.resolve("VENUE.md"), venueCard(venue, title), StandardCharsets.UTF_8);
            out.put("ok", true);
            out.put("venue", venue);
            out.put("dir", destDir.toAbsolutePath().toString());
            out.put("mainTex", destDir.resolve("main.tex").toString());
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    public Map<String, Object> compile(Path texDir) {
        return new LatexPackager().tryCompile(texDir);
    }

    private void copySpringerCls(Path dest, String venueId) {
        if (!"springer-sn".equals(venueId)) return;
        Path kit = Path.of(workspaceDir, "paper", "template");
        if (!Files.isDirectory(kit)) kit = Path.of(workspaceDir, "paper");
        for (String name : List.of("sn-jnl.cls", "sn-basic.bst", "sn-bibliography.bib")) {
            Path f = kit.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    Files.copy(f, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String fill(String tex, String title) {
        String t = title == null || title.isBlank() ? "Working title" : title.trim();
        return tex.replace("{{TITLE}}", escapeLatex(t))
                .replace("{{ABSTRACT}}", "TODO: one-paragraph claim. Do not invent numbers.")
                .replace("{{INTRO}}", "Motivation and gap. Cite only verified items in the bibliography.")
                .replace("{{RELATED}}", "Synthesize, do not list. Every cite needs DOI or arXiv.")
                .replace("{{METHOD}}", "What was actually run. No novel-sampler claims without code.")
                .replace("{{EXPERIMENTS}}", "Tables and figures go in \\texttt{figures/}. Numbers from CSV only.")
                .replace("{{CONCLUSION}}", "Limits first. No unverified claims.");
    }

    private static String venueCard(Map<String, Object> venue, String title) {
        return "# Target venue\n\n"
                + "- **id:** " + venue.get("id") + "\n"
                + "- **name:** " + venue.get("name") + "\n"
                + "- **full:** " + venue.get("fullName") + "\n"
                + "- **kind:** " + venue.get("kind") + "\n"
                + "- **tier:** " + venue.get("tier") + "\n"
                + "- **looks like:** " + venue.get("looksLike") + "\n"
                + "- **official:** " + venue.get("officialUrl") + "\n"
                + "- **working title:** " + (title == null ? "" : title) + "\n\n"
                + "If this is `ekina-letters`, it is a lab serial. Body must stay IEEEtran; do not invent a class. "
                + "Cover: `\\onecolumn\\includepdf[pages=1]{figures/cover}\\twocolumn`. "
                + "Never `\\includegraphics[width=\\paperwidth]` in two-column mode.\n";
    }

    private static String escapeLatex(String s) {
        return s.replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
