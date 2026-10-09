package com.ekina.application.paper;

import org.springframework.beans.factory.annotation.Value;
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
import java.util.stream.Stream;

/**
 * Fail-closed layout / figure / venue checklist for lab papers and pipeline papers.
 * Does not invent numbers. A paper is not "done" until {@code pass=true}.
 */
@Service
public class PaperAuditService {

    private static final Pattern INCLUDE = Pattern.compile(
            "\\\\includegraphics(?:\\[[^]]*])?\\{([^}]+)}");
    private static final Pattern BOLD_NUM = Pattern.compile(
            "\\\\mathbf\\{(\\d+\\.\\d+)}|\\b(\\d{2}\\.\\d{3})\\b");
    private static final Pattern BARE_ARTICLE = Pattern.compile(
            "\\\\documentclass(?:\\[[^]]*])?\\{article}");

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    public Path resolvePaperDir(String paperDir) {
        Path academic = Path.of(agentHome, "lab", "academic");
        if (paperDir == null || paperDir.isBlank()) {
            Path ddpm = academic.resolve("ddpm-sampling").resolve("paper");
            return Files.isDirectory(ddpm) ? ddpm : academic;
        }
        Path p = Path.of(paperDir.trim());
        if (Files.isDirectory(p)) {
            return locateRoot(p);
        }
        Path bySlug = academic.resolve(paperDir.trim()).resolve("paper");
        if (Files.isDirectory(bySlug)) return bySlug;
        String slug = paperDir.trim();
        if (slug.startsWith("pf_")) slug = slug.substring(3);
        Path hyphen = academic.resolve(slug.replace('_', '-')).resolve("paper");
        if (Files.isDirectory(hyphen)) return hyphen;
        Path under = academic.resolve(slug.replace('-', '_')).resolve("paper");
        if (Files.isDirectory(under)) return under;
        return hyphen;
    }

    public Map<String, Object> audit(String paperDir) {
        return audit(resolvePaperDir(paperDir));
    }

    public Map<String, Object> audit(Path root) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        Path paper = locateRoot(root);
        Path main = findMainTex(paper);
        String tex = read(main);
        String venue = read(findVenue(paper));
        Path figures = findFigures(paper);
        Path script = findFigureScript(paper);

        boolean hasMain = Files.isRegularFile(main);
        checks.add(check("tex_present", hasMain, hasMain ? rel(paper, main) : "missing main.tex"));

        boolean hasVenue = !venue.isBlank();
        checks.add(check("venue_declared", hasVenue,
                hasVenue ? "VENUE.md present" : "missing VENUE.md — refuse to call this camera-ready"));

        boolean bare = hasMain && BARE_ARTICLE.matcher(tex).find() && !tex.contains("neurips")
                && !tex.contains("icml") && !tex.contains("iclr");
        boolean identifiable = hasMain && !bare && (
                tex.contains("ekina-letters") || tex.contains("sn-jnl") || tex.contains("IEEEtran")
                        || tex.contains("elsarticle") || tex.contains("acl") || tex.contains("aaai")
                        || tex.toLowerCase(Locale.ROOT).contains("neurips")
                        || tex.toLowerCase(Locale.ROOT).contains("icml"));
        checks.add(check("no_bare_article", identifiable,
                identifiable ? "documentclass is venue-identifiable" : "bare article or unknown class"));

        boolean coverFile = Files.isRegularFile(figures.resolve("cover.jpg"))
                || Files.isRegularFile(figures.resolve("cover.png"))
                || Files.isRegularFile(figures.resolve("cover.pdf"));
        boolean coverCited = tex.contains("{cover}") || tex.contains("{figures/cover}");
        boolean coverBleedHack = tex.contains("width=\\paperwidth") && tex.contains("includegraphics")
                && !tex.contains("includepdf");
        boolean coverPdfpages = tex.contains("includepdf") && coverCited;
        boolean serial = venue.toLowerCase(Locale.ROOT).contains("ekina-letters")
                || tex.contains("Ekina Research Letters") || tex.contains("sn-jnl");
        boolean coverOk = !serial || (coverFile && coverCited && !coverBleedHack);
        String coverDetail;
        if (!serial) {
            coverDetail = "not a serial issue — cover optional";
        } else if (coverBleedHack) {
            coverDetail = "broken: \\includegraphics[width=\\paperwidth] in IEEEtran twocolumn; use \\onecolumn\\includepdf{figures/cover}\\twocolumn";
        } else if (coverCited && coverFile) {
            coverDetail = coverPdfpages
                    ? "cover via includepdf after begin{document}"
                    : "cover cited after begin{document}";
        } else {
            coverDetail = "lab serial needs figures/cover and \\onecolumn\\includepdf{figures/cover}\\twocolumn";
        }
        checks.add(check("cover_in_tex", coverOk, coverDetail));

        List<String> missingFigs = new ArrayList<>();
        List<String> cited = new ArrayList<>();
        Matcher im = INCLUDE.matcher(tex);
        while (im.find()) {
            String name = im.group(1).trim();
            if (name.endsWith(".pdf") || name.endsWith(".png") || name.endsWith(".jpg")) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            cited.add(name);
            if (!figureExists(figures, Path.of(name).getFileName().toString())
                    && !figureExists(paper, name)) {
                missingFigs.add(name);
            }
        }
        boolean figsCited = !cited.isEmpty() && missingFigs.isEmpty();
        checks.add(check("figures_on_disk", figsCited,
                cited.isEmpty() ? "no \\includegraphics" : (missingFigs.isEmpty()
                        ? ("all " + cited.size() + " includes exist")
                        : ("missing " + missingFigs))));

        boolean scriptOk = Files.isRegularFile(script);
        String scriptTxt = read(script);
        boolean fromCsv = scriptOk && (scriptTxt.contains("csv") || scriptTxt.contains("CSV"));
        checks.add(check("figures_from_csv_script", fromCsv,
                fromCsv ? rel(paper, script) : "missing scripts/make_figures.py that reads CSV"));

        boolean ugly = scriptOk && (scriptTxt.contains("jet") || scriptTxt.contains("rainbow")
                || scriptTxt.contains("YlOrRd") || scriptTxt.contains("RdYlGn"));
        checks.add(check("colorblind_palette", scriptOk && !ugly,
                !scriptOk ? "no figure script" : (ugly
                        ? "jet/rainbow/YlOrRd in script — not publication style"
                        : "no jet/rainbow as sole encoding")));

        boolean booktabs = tex.contains("booktabs") || tex.contains("\\toprule");
        checks.add(check("booktabs_tables", booktabs, booktabs ? "toprule/midrule" : "tables not booktabs"));

        boolean numbersMatch = csvBackedNumbers(paper, tex);
        boolean numbersOk = numbersMatch || tex.isBlank();
        checks.add(check("numbers_in_csv", numbersOk,
                tex.isBlank() ? "no tex yet"
                        : (numbersMatch ? "bold/3dp FID values appear in data/*.csv"
                        : "claimed FID values not found in CSV — do not invent")));

        boolean pass = hasMain && hasVenue && identifiable && coverOk && figsCited
                && fromCsv && !ugly && booktabs && numbersMatch;
        int ok = 0;
        for (Map<String, Object> c : checks) {
            if (Boolean.TRUE.equals(c.get("ok"))) ok++;
        }
        out.put("pass", pass);
        out.put("score", ok);
        out.put("maxScore", checks.size());
        out.put("paperDir", paper.toAbsolutePath().toString());
        out.put("mainTex", hasMain ? main.toAbsolutePath().toString() : "");
        out.put("checks", checks);
        out.put("citedFigures", cited);
        if (!pass) {
            out.put("verdict", "未过验收：禁止对主人说写完了/排好了。缺项派 SCRIBE 补，数字仍由伊姬娜核 CSV。");
        } else {
            out.put("verdict", "版式/封面/图源/数字出处过关。投稿换会另选 venue，本期连载不算相机就绪顶会稿。");
        }
        return out;
    }

    /** Extra gate checks merged into {@link GateKeeper} for pipeline roots. */
    public List<Map<String, Object>> pipelineChecks(Path root) {
        Map<String, Object> a = audit(root);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) a.getOrDefault("checks", List.of());
        List<Map<String, Object>> keep = new ArrayList<>();
        for (Map<String, Object> c : checks) {
            String name = String.valueOf(c.get("name"));
            if (name.equals("venue_declared") || name.equals("no_bare_article")
                    || name.equals("figures_on_disk") || name.equals("cover_in_tex")) {
                keep.add(c);
            }
        }
        return keep;
    }

    public boolean pipelinePass(Path root, PaperMode mode) {
        if (mode == PaperMode.SMOKE) return true;
        for (Map<String, Object> c : pipelineChecks(root)) {
            if (!Boolean.TRUE.equals(c.get("ok"))) {
                String name = String.valueOf(c.get("name"));
                if ("cover_in_tex".equals(name)) continue;
                return false;
            }
        }
        return true;
    }

    private boolean csvBackedNumbers(Path paper, String tex) {
        if (tex.isBlank()) return false;
        Path data = paper.resolve("data");
        if (!Files.isDirectory(data)) data = paper.getParent() == null ? data : paper.getParent().resolve("data");
        StringBuilder csv = new StringBuilder();
        if (Files.isDirectory(data)) {
            try (Stream<Path> s = Files.list(data)) {
                s.filter(p -> p.getFileName().toString().endsWith(".csv")).forEach(p -> csv.append(read(p)));
            } catch (Exception ignored) {
            }
        }
        if (csv.isEmpty()) {
            return !BOLD_NUM.matcher(tex).find();
        }
        String blob = csv.toString();
        Matcher m = BOLD_NUM.matcher(tex);
        int need = 0;
        int hit = 0;
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (n == null) continue;
            need++;
            if (blob.contains(n)) hit++;
        }
        return need == 0 || hit >= Math.max(1, need - 1);
    }

    private static Path locateRoot(Path p) {
        if (Files.isRegularFile(p.resolve("main.tex")) || Files.isRegularFile(p.resolve("VENUE.md"))) return p;
        if (Files.isRegularFile(p.resolve("paper").resolve("main.tex"))) return p.resolve("paper");
        if (Files.isRegularFile(p.resolve("tex").resolve("main.tex"))) return p;
        return p;
    }

    private static Path findMainTex(Path paper) {
        if (Files.isRegularFile(paper.resolve("main.tex"))) return paper.resolve("main.tex");
        return paper.resolve("tex").resolve("main.tex");
    }

    private static Path findVenue(Path paper) {
        if (Files.isRegularFile(paper.resolve("VENUE.md"))) return paper.resolve("VENUE.md");
        if (Files.isRegularFile(paper.resolve("tex").resolve("VENUE.md"))) return paper.resolve("tex").resolve("VENUE.md");
        return paper.resolve("VENUE.md");
    }

    private static Path findFigures(Path paper) {
        if (Files.isDirectory(paper.resolve("figures"))) return paper.resolve("figures");
        return paper.resolve("tex").resolve("figures");
    }

    private static Path findFigureScript(Path paper) {
        Path a = paper.resolve("scripts").resolve("make_figures.py");
        if (Files.isRegularFile(a)) return a;
        return paper.resolve("make_figures.py");
    }

    private static boolean figureExists(Path figures, String stem) {
        String s = stem.contains("/") || stem.contains("\\")
                ? Path.of(stem).getFileName().toString() : stem;
        for (String ext : List.of("", ".pdf", ".png", ".jpg", ".jpeg")) {
            if (Files.isRegularFile(figures.resolve(s + ext))) return true;
            if (Files.isRegularFile(figures.resolve(s))) return true;
        }
        return false;
    }

    private static String read(Path p) {
        try {
            if (p != null && Files.isRegularFile(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName().toString();
        }
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }
}
