package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds official / community code repos for a paper (PapersWithCode + arXiv + GitHub).
 * Ranks against awesome-lists / survey dumps; prefers repos that look trainable.
 */
@Service
public class CodeRepoFinder {

    private static final Pattern GITHUB = Pattern.compile(
            "https?://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> BAD_NAME_BITS = Set.of(
            "awesome", "awesome-", "paper-list", "papers", "survey", "reading-list",
            "must-read", "collection", "curated", "resources", "links", "bookmark");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> findFor(LitPaper paper) {
        Map<String, Object> out = new LinkedHashMap<>();
        // url -> best source rank (lower = better source)
        Map<String, Integer> sourceRank = new LinkedHashMap<>();
        Map<String, String> sourceName = new LinkedHashMap<>();

        if (paper.getCodeUrl() != null && !paper.getCodeUrl().isBlank()) {
            addRepo(sourceRank, sourceName, paper.getCodeUrl(), "lit.codeUrl", 0);
        }

        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) {
            Map<String, Object> pwc = fromPapersWithCode(paper.getArxivId());
            out.put("papersWithCode", pwc);
            Object urls = pwc.get("repos");
            if (urls instanceof List<?> list) {
                int i = 0;
                for (Object u : list) {
                    addRepo(sourceRank, sourceName, String.valueOf(u), "paperswithcode", 1 + Math.min(i, 3));
                    i++;
                }
            }
            for (String u : fromArxivAbsHtml(paper.getArxivId())) {
                addRepo(sourceRank, sourceName, u, "arxiv_abs_html", 2);
            }
        }

        if (paper.getAbstractText() != null) {
            for (String u : extractGithub(paper.getAbstractText())) {
                addRepo(sourceRank, sourceName, u, "abstract", 3);
            }
        }

        // GitHub search only if still empty or weak
        boolean needGh = sourceRank.isEmpty()
                || sourceRank.keySet().stream().allMatch(CodeRepoFinder::looksLikeListRepo);
        if (needGh && paper.getTitle() != null && !paper.getTitle().isBlank()) {
            for (String u : fromGithubSearch(paper.getTitle())) {
                addRepo(sourceRank, sourceName, u, "github_search", 8);
            }
        }

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (String url : sourceRank.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", url);
            row.put("source", sourceName.get(url));
            row.put("sourceRank", sourceRank.get(url));
            int q = qualityScore(url);
            row.put("qualityScore", q);
            row.put("listLike", looksLikeListRepo(url));
            // lightweight metadata probe (best-effort)
            Map<String, Object> meta = probeGithubRepo(url);
            row.put("meta", meta);
            int trainBonus = Boolean.TRUE.equals(meta.get("likelyTrainable")) ? 40 : 0;
            int listPenalty = looksLikeListRepo(url) ? -80 : 0;
            int total = q + trainBonus + listPenalty - sourceRank.get(url) * 3;
            row.put("totalScore", total);
            ranked.add(row);
        }
        ranked.sort(Comparator.comparingInt((Map<String, Object> m) -> toInt(m.get("totalScore"))).reversed());

        // Drop pure list repos unless nothing else
        List<Map<String, Object>> preferred = ranked.stream()
                .filter(m -> !Boolean.TRUE.equals(m.get("listLike")))
                .toList();
        List<Map<String, Object>> finalList = preferred.isEmpty() ? ranked : preferred;

        List<String> repos = new ArrayList<>();
        for (Map<String, Object> m : finalList) {
            repos.add(String.valueOf(m.get("url")));
        }

        out.put("ranked", finalList.size() > 8 ? finalList.subList(0, 8) : finalList);
        out.put("repos", repos);
        out.put("primary", repos.isEmpty() ? null : repos.get(0));
        out.put("sources", new ArrayList<>(new LinkedHashSet<>(sourceName.values())));
        out.put("ok", !repos.isEmpty() && !looksLikeListRepo(repos.get(0)));
        // still ok=true if only list remains, but flag weak
        if (!repos.isEmpty() && looksLikeListRepo(repos.get(0))) {
            out.put("ok", true);
            out.put("weakPrimary", true);
            out.put("weakReason", "only list-like repos found");
        }
        return out;
    }

    private void addRepo(Map<String, Integer> sourceRank, Map<String, String> sourceName,
                         String raw, String source, int rank) {
        String n = normalizeGithub(raw);
        if (n == null) return;
        Integer prev = sourceRank.get(n);
        if (prev == null || rank < prev) {
            sourceRank.put(n, rank);
            sourceName.put(n, source);
        }
    }

    private Map<String, Object> fromPapersWithCode(String arxivId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> repos = new ArrayList<>();
        try {
            String id = arxivId.trim();
            String url = "https://paperswithcode.com/api/v1/papers/?arxiv_id="
                    + URLEncoder.encode(id, StandardCharsets.UTF_8);
            JsonNode root = getJson(url);
            JsonNode results = root.path("results");
            if (results.isArray() && results.size() > 0) {
                String paperId = results.get(0).path("id").asText("");
                m.put("pwcPaperId", paperId);
                m.put("title", results.get(0).path("title").asText(""));
                if (!paperId.isBlank()) {
                    JsonNode reposNode = getJson(
                            "https://paperswithcode.com/api/v1/papers/" + paperId + "/repositories/");
                    for (JsonNode r : reposNode.path("results")) {
                        String u = r.path("url").asText("");
                        String n = normalizeGithub(u);
                        if (n != null) repos.add(n);
                        if (Boolean.TRUE.equals(r.path("is_official").asBoolean(false)) && n != null) {
                            repos.remove(n);
                            repos.add(0, n);
                        }
                    }
                }
            }
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        m.put("repos", repos);
        return m;
    }

    private List<String> fromGithubSearch(String title) {
        List<String> out = new ArrayList<>();
        try {
            String[] toks = title.replaceAll("[^A-Za-z0-9\\s-]", " ").trim().split("\\s+");
            StringBuilder q = new StringBuilder();
            int n = 0;
            for (String t : toks) {
                if (t.length() < 3) continue;
                String low = t.toLowerCase(Locale.ROOT);
                if (BAD_NAME_BITS.contains(low)) continue;
                if (q.length() > 0) q.append(' ');
                q.append(t);
                if (++n >= 5) break;
            }
            if (q.length() < 3) return out;
            // Exclude awesome-lists in query
            String query = q + " language:Python -awesome -survey -\"reading-list\" -\"paper-list\"";
            String url = "https://api.github.com/search/repositories?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&sort=stars&order=desc&per_page=8";
            JsonNode root = getJson(url);
            for (JsonNode item : root.path("items")) {
                String html = item.path("html_url").asText("");
                String repoUrl = normalizeGithub(html);
                if (repoUrl == null || looksLikeListRepo(repoUrl)) continue;
                if (!out.contains(repoUrl)) out.add(repoUrl);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private Map<String, Object> probeGithubRepo(String repoUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String path = repoUrl.replace("https://github.com/", "");
            JsonNode root = getJson("https://api.github.com/repos/" + path);
            m.put("stars", root.path("stargazers_count").asInt(0));
            m.put("description", root.path("description").asText(""));
            m.put("language", root.path("language").asText(""));
            String desc = (root.path("description").asText("") + " " + path).toLowerCase(Locale.ROOT);
            boolean listLike = looksLikeListRepo(repoUrl) || desc.contains("awesome") || desc.contains("paper list");
            m.put("listLike", listLike);

            // contents root
            JsonNode contents = getJson("https://api.github.com/repos/" + path + "/contents/");
            boolean hasReq = false, hasTrain = false, hasPy = false;
            if (contents.isArray()) {
                for (JsonNode c : contents) {
                    String name = c.path("name").asText("").toLowerCase(Locale.ROOT);
                    if (name.equals("requirements.txt") || name.equals("pyproject.toml")
                            || name.equals("setup.py") || name.equals("environment.yml")) hasReq = true;
                    if (name.contains("train") || name.equals("main.py") || name.equals("run.py")
                            || name.contains("finetune")) hasTrain = true;
                    if (name.endsWith(".py")) hasPy = true;
                }
            }
            m.put("hasRequirements", hasReq);
            m.put("hasTrainEntry", hasTrain);
            m.put("hasPython", hasPy);
            m.put("likelyTrainable", !listLike && (hasTrain || hasReq) && hasPy);
        } catch (Exception e) {
            m.put("error", e.getMessage());
            m.put("likelyTrainable", false);
        }
        return m;
    }

    static boolean looksLikeListRepo(String url) {
        if (url == null) return true;
        String u = url.toLowerCase(Locale.ROOT);
        for (String b : BAD_NAME_BITS) {
            if (u.contains(b)) return true;
        }
        return false;
    }

    static int qualityScore(String url) {
        if (url == null) return -100;
        String u = url.toLowerCase(Locale.ROOT);
        int s = 10;
        if (looksLikeListRepo(u)) s -= 50;
        if (u.contains("/microsoft/") || u.contains("/google/") || u.contains("/facebookresearch/")
                || u.contains("/huggingface/") || u.contains("/openai/") || u.contains("/deepmind/")
                || u.contains("/meta-llama/") || u.contains("/pytorch/")) s += 25;
        if (u.contains("lora") || u.contains("peft") || u.contains("adapter") || u.contains("diffuser")) s += 8;
        if (u.contains("impl") || u.contains("official") || u.contains("code")) s += 5;
        return s;
    }

    private List<String> fromArxivAbsHtml(String arxivId) {
        try {
            String html = getText("https://arxiv.org/abs/" + arxivId.trim());
            return extractGithub(html);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> extractGithub(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = GITHUB.matcher(text);
        while (m.find()) {
            String n = normalizeGithub(m.group());
            if (n != null && !out.contains(n)) out.add(n);
        }
        return out;
    }

    static String normalizeGithub(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.trim().replaceAll("[)\\],.;]+$", "");
        Matcher m = GITHUB.matcher(u);
        if (!m.find()) return null;
        String g = m.group().replace("http://", "https://");
        if (g.toLowerCase(Locale.ROOT).endsWith(".git")) {
            g = g.substring(0, g.length() - 4);
        }
        g = g.replaceAll("(?i)/(tree|blob|issues|pull)/.*$", "");
        return g;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + url);
        }
        String body = resp.body() == null ? "" : resp.body().trim();
        if (body.startsWith("<")) {
            throw new IllegalStateException("Non-JSON HTML response from " + url);
        }
        return mapper.readTree(body);
    }

    private String getText(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + url);
        }
        return resp.body();
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
