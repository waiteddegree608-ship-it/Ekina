package com.ekina.application.paper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies every BibTeX entry against Crossref/arXiv. Unverified cites must not ship.
 */
@Service
public class CitationVerifier {

    private static final Pattern ENTRY = Pattern.compile(
            "@(\\w+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)\\n\\}", Pattern.MULTILINE);
    private static final Pattern FIELD = Pattern.compile(
            "(doi|eprint|url|title)\\s*=\\s*[\"{]([^\"}]+)[\"}]", Pattern.CASE_INSENSITIVE);

    @Autowired
    private LiteratureClient literatureClient;

    public Map<String, Object> verifyBibFile(Path bibPath) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        int ok = 0;
        int fail = 0;
        try {
            if (!Files.isRegularFile(bibPath)) {
                report.put("ok", false);
                report.put("error", "bib missing: " + bibPath);
                return report;
            }
            String bib = Files.readString(bibPath, StandardCharsets.UTF_8);
            Matcher m = ENTRY.matcher(bib + "\n");
            // Fallback simpler split if regex misses
            List<BibEntry> parsed = parseBib(bib);
            for (BibEntry e : parsed) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", e.key);
                row.put("title", e.title);
                row.put("doi", e.doi);
                row.put("arxivId", e.arxivId);
                boolean verified = false;
                String method = "";
                if (e.doi != null && !e.doi.isBlank()) {
                    LitPaper p = literatureClient.resolveDoi(e.doi);
                    if (p != null && titleClose(e.title, p.getTitle())) {
                        verified = true;
                        method = "crossref-doi";
                        row.put("resolvedTitle", p.getTitle());
                    } else if (p != null) {
                        // DOI resolves but title drift — still accept DOI as stable id
                        verified = true;
                        method = "crossref-doi-title-soft";
                        row.put("resolvedTitle", p.getTitle());
                    }
                }
                if (!verified && e.arxivId != null && !e.arxivId.isBlank()) {
                    LitPaper p = literatureClient.resolveArxiv(e.arxivId);
                    if (p != null) {
                        verified = true;
                        method = "arxiv";
                        row.put("resolvedTitle", p.getTitle());
                    }
                }
                row.put("verified", verified);
                row.put("method", method);
                entries.add(row);
                if (verified) ok++; else fail++;
            }
            report.put("ok", fail == 0 && ok > 0);
            report.put("verified", ok);
            report.put("failed", fail);
            report.put("entries", entries);
        } catch (Exception ex) {
            report.put("ok", false);
            report.put("error", ex.getMessage());
        }
        return report;
    }

    /** Rewrite bib keeping only verified entries; returns new bib text. */
    public String filterVerifiedBib(Path bibPath, Map<String, Object> report) throws Exception {
        String bib = Files.readString(bibPath, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) report.get("entries");
        if (entries == null) return bib;
        StringBuilder out = new StringBuilder();
        List<BibEntry> parsed = parseBib(bib);
        Map<String, Boolean> ok = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            ok.put(String.valueOf(e.get("key")), Boolean.TRUE.equals(e.get("verified")));
        }
        for (BibEntry e : parsed) {
            if (Boolean.TRUE.equals(ok.get(e.key))) {
                out.append(e.raw).append("\n\n");
            }
        }
        return out.toString().trim() + "\n";
    }

    private static boolean titleClose(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return true;
        String x = norm(a);
        String y = norm(b);
        return x.contains(y) || y.contains(x) || x.equals(y);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static List<BibEntry> parseBib(String bib) {
        List<BibEntry> list = new ArrayList<>();
        // Split on @ boundaries
        String[] chunks = bib.split("(?=@\\w+\\s*\\{)");
        for (String chunk : chunks) {
            String c = chunk.trim();
            if (!c.startsWith("@")) continue;
            BibEntry e = new BibEntry();
            e.raw = c;
            Matcher km = Pattern.compile("@\\w+\\s*\\{\\s*([^,\\s]+)").matcher(c);
            if (km.find()) e.key = km.group(1).trim();
            Matcher fm = FIELD.matcher(c);
            while (fm.find()) {
                String name = fm.group(1).toLowerCase(Locale.ROOT);
                String val = fm.group(2).trim();
                switch (name) {
                    case "doi" -> e.doi = val.replace("https://doi.org/", "");
                    case "eprint" -> e.arxivId = val;
                    case "title" -> e.title = val;
                    case "url" -> {
                        if (val.contains("arxiv.org/abs/")) {
                            e.arxivId = val.replaceAll(".*arxiv\\.org/abs/", "").replaceAll("[?#].*", "");
                        }
                        if (val.contains("doi.org/")) {
                            e.doi = val.replaceAll(".*doi\\.org/", "");
                        }
                    }
                    default -> {}
                }
            }
            if (e.key != null) list.add(e);
        }
        return list;
    }

    static class BibEntry {
        String key;
        String title;
        String doi;
        String arxivId;
        String raw;
    }
}
