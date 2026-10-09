package com.ekina.application.paper;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads paper text from real sources (arXiv abs HTML; optional PDF via pdftotext).
 * Extracts coarse section cues for outline / related-work — not LLM hallucination.
 */
@Service
public class PaperReader {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DATASET = Pattern.compile(
            "(?i)\\b(CIFAR-?10|CIFAR-?100|ImageNet|MNIST|Fashion-MNIST|COCO|WikiText|GLUE|SQuAD|"
                    + "KITTI|Cityscapes|LibriSpeech|AudioSet|OpenWebText|C4|LAION)[\\w-]*\\b");
    private static final Pattern METHOD = Pattern.compile(
            "(?i)\\b(transformer|attention|diffusion|LoRA|adapter|GAN|VAE|GNN|CNN|ResNet|"
                    + "BERT|GPT|ViT|U-Net|contrastive|self-supervised|fine-tun\\w*)\\b");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> readAndNote(LitPaper paper, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", paper.getId());
        note.put("title", paper.getTitle());
        note.put("year", paper.getYear());
        note.put("venue", paper.getVenue());
        note.put("doi", paper.getDoi());
        note.put("arxivId", paper.getArxivId());
        note.put("tldr", paper.getTldr());
        note.put("citationCount", paper.getCitationCount());

        String abstractText = paper.getAbstractText();
        String absHtmlText = null;
        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) {
            try {
                String html = getText("https://arxiv.org/abs/" + paper.getArxivId().trim());
                absHtmlText = stripHtml(html);
                Files.writeString(outDir.resolve("arxiv_abs.txt"), absHtmlText, StandardCharsets.UTF_8);
                note.put("arxivAbsSaved", true);
                if (abstractText == null || abstractText.isBlank()) {
                    abstractText = extractBetween(absHtmlText, "Abstract:", "Subjects:");
                }
            } catch (Exception e) {
                note.put("arxivAbsError", e.getMessage());
            }
            // Best-effort PDF → text if pdftotext exists
            Map<String, Object> pdf = tryPdfText(paper.getArxivId(), outDir);
            note.put("pdf", pdf);
            if (Boolean.TRUE.equals(pdf.get("ok")) && pdf.get("excerpt") instanceof String ex) {
                note.put("pdfExcerptChars", ((String) ex).length());
            }
        }

        note.put("abstract", abstractText);
        String corpus = (abstractText == null ? "" : abstractText) + "\n"
                + (absHtmlText == null ? "" : absHtmlText.substring(0, Math.min(8000, absHtmlText.length())));
        note.put("datasetsMentioned", findAll(DATASET, corpus, 12));
        note.put("methodKeywords", findAll(METHOD, corpus, 16));
        note.put("claimsHeuristic", heuristicClaims(abstractText));

        String md = toMarkdown(note);
        Files.writeString(outDir.resolve("reading.md"), md, StandardCharsets.UTF_8);
        note.put("readingMd", "reading.md");
        return note;
    }

    private Map<String, Object> tryPdfText(String arxivId, Path outDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        Path pdf = outDir.resolve("paper.pdf");
        Path txt = outDir.resolve("paper.txt");
        try {
            byte[] bytes = getBytes("https://arxiv.org/pdf/" + arxivId.trim() + ".pdf");
            if (bytes.length < 1000) {
                m.put("ok", false);
                m.put("error", "pdf too small");
                return m;
            }
            Files.write(pdf, bytes);
            m.put("pdfBytes", bytes.length);
            ProcessBuilder pb = new ProcessBuilder("pdftotext", "-l", "4", pdf.toString(), txt.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                m.put("ok", false);
                m.put("error", "pdftotext timeout");
                return m;
            }
            if (p.exitValue() == 0 && Files.isRegularFile(txt)) {
                String text = Files.readString(txt, StandardCharsets.UTF_8);
                m.put("ok", true);
                m.put("excerpt", text.length() > 12000 ? text.substring(0, 12000) : text);
                return m;
            }
            m.put("ok", false);
            m.put("error", "pdftotext exit=" + p.exitValue());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
            m.put("note", "Install poppler pdftotext for PDF extraction; abs HTML still used.");
        }
        return m;
    }

    private static List<String> findAll(Pattern p, String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = p.matcher(text);
        while (m.find() && out.size() < max) {
            String g = m.group();
            boolean dup = out.stream().anyMatch(x -> x.equalsIgnoreCase(g));
            if (!dup) out.add(g);
        }
        return out;
    }

    private static List<String> heuristicClaims(String abs) {
        List<String> claims = new ArrayList<>();
        if (abs == null || abs.isBlank()) return claims;
        String[] sentences = abs.split("(?<=[.!?])\\s+");
        for (String s : sentences) {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.contains("propose") || lower.contains("we introduce") || lower.contains("outperform")
                    || lower.contains("state-of-the-art") || lower.contains("sota")
                    || lower.contains("achieve") || lower.contains("novel")) {
                claims.add(s.trim());
            }
            if (claims.size() >= 5) break;
        }
        return claims;
    }

    private static String toMarkdown(Map<String, Object> note) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(note.getOrDefault("title", "")).append("\n\n");
        sb.append("- id: ").append(note.get("id")).append("\n");
        if (note.get("arxivId") != null) sb.append("- arxiv: ").append(note.get("arxivId")).append("\n");
        if (note.get("doi") != null) sb.append("- doi: ").append(note.get("doi")).append("\n");
        sb.append("- venue: ").append(note.get("venue")).append(" year: ").append(note.get("year")).append("\n");
        if (note.get("tldr") != null) sb.append("\n**TLDR:** ").append(note.get("tldr")).append("\n");
        sb.append("\n## Abstract\n\n").append(note.getOrDefault("abstract", "")).append("\n\n");
        sb.append("## Datasets mentioned\n\n");
        for (Object d : (List<?>) note.getOrDefault("datasetsMentioned", List.of())) {
            sb.append("- ").append(d).append("\n");
        }
        sb.append("\n## Method keywords\n\n");
        for (Object d : (List<?>) note.getOrDefault("methodKeywords", List.of())) {
            sb.append("- ").append(d).append("\n");
        }
        sb.append("\n## Claim-like sentences (heuristic)\n\n");
        for (Object d : (List<?>) note.getOrDefault("claimsHeuristic", List.of())) {
            sb.append("> ").append(d).append("\n\n");
        }
        return sb.toString();
    }

    private static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ");
        s = TAG.matcher(s).replaceAll(" ");
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static String extractBetween(String text, String a, String b) {
        if (text == null) return null;
        int i = text.indexOf(a);
        if (i < 0) return null;
        int j = text.indexOf(b, i + a.length());
        String slice = j < 0 ? text.substring(i + a.length()) : text.substring(i + a.length(), j);
        return slice.trim();
    }

    private String getText(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)")
                .GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }
}
