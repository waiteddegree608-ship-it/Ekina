package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real literature sources only (Semantic Scholar / OpenAlex / arXiv).
 * Never invent papers — search APIs and keep only records with DOI or arXiv id.
 */
@Service
public class LiteratureClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Curated venue substring → tier. "A" ≈ top conference/journal (顶会/顶刊). */
    private static final String[][] VENUE_RULES = {
            {"computer vision and pattern recognition", "A"},
            {"cvpr", "A"},
            {"international conference on computer vision", "A"},
            {"iccv", "A"},
            {"european conference on computer vision", "A"},
            {"eccv", "A"},
            {"neural information processing systems", "A"},
            {"neurips", "A"},
            {"advances in neural information", "A"},
            {"international conference on machine learning", "A"},
            {"icml", "A"},
            {"international conference on learning representations", "A"},
            {"iclr", "A"},
            {"association for computational linguis", "A"},
            {"empirical methods in natural language", "A"},
            {"naacl", "A"},
            {"association for the advancement of artificial", "A"},
            {"aaai", "A"},
            {"international joint conference on artificial", "A"},
            {"ijcai", "A"},
            {"knowledge discovery and data mining", "A"},
            {"sigir", "A"},
            {"world wide web conference", "A"},
            {"transactions on machine learning research", "A"},
            {"journal of machine learning research", "A"},
            {"pattern analysis and machine intelligence", "A"},
            {"conference on language modeling", "A"},
            {"british machine vision", "B"},
            {"applications of computer vision", "B"},
            {"international conference on acoustics", "B"},
            {"interspeech", "B"},
    };

    private static String classifyVenue(String venue) {
        if (venue == null || venue.isBlank()) return null;
        String v = venue.toLowerCase(Locale.ROOT);
        for (String[] r : VENUE_RULES) {
            if (v.contains(r[0])) return r[1];
        }
        return null;
    }

    private static void applyVenueTier(LitPaper p) {
        String tier = classifyVenue(p.getVenue());
        p.setVenueTier(tier);
        p.setTopVenue("A".equals(tier));
    }

    @Value("${ekina.paper.semanticscholar-api-key:}")
    private String s2ApiKey;

    /** Explicit minimum year override; 0 = auto "recent two years" (currentYear - 2). */
    @Value("${ekina.paper.min-year:0}")
    private int minYear;

    /** Effective publication-year floor: explicit config if set (>0), else the last two years. */
    private int effectiveMinYear() {
        if (minYear > 0) return minYear;
        return java.time.Year.now().getValue() - 2;
    }

    public List<LitPaper> search(String query, int limit) {
        int n = Math.max(3, Math.min(30, limit));
        Map<String, LitPaper> byKey = new LinkedHashMap<>();
        for (LitPaper p : searchSemanticScholar(query, n)) {
            putUnique(byKey, p);
        }
        if (byKey.size() < n) {
            for (LitPaper p : searchOpenAlex(query, n)) {
                putUnique(byKey, p);
            }
        }
        if (byKey.size() < Math.max(5, n / 2)) {
            for (LitPaper p : searchArxiv(query, n)) {
                putUnique(byKey, p);
            }
        }
        List<LitPaper> out = new ArrayList<>(byKey.values());
        // Prefer top venue (顶会/顶刊) first, then recent.
        out.sort((a, b) -> {
            int ta = a.isTopVenue() ? 1 : 0;
            int tb = b.isTopVenue() ? 1 : 0;
            if (ta != tb) return Integer.compare(tb, ta);
            int ya = a.getYear() == null ? 0 : a.getYear();
            int yb = b.getYear() == null ? 0 : b.getYear();
            return Integer.compare(yb, ya);
        });
        if (out.size() > n) return out.subList(0, n);
        return out;
    }

    public LitPaper resolveDoi(String doi) {
        if (doi == null || doi.isBlank()) return null;
        String clean = doi.trim().replace("https://doi.org/", "");
        try {
            String url = "https://api.crossref.org/works/" + URLEncoder.encode(clean, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            JsonNode root = getJson(url, Map.of("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)"));
            JsonNode msg = root.path("message");
            if (msg.isMissingNode()) return null;
            LitPaper p = new LitPaper();
            p.setSource("crossref");
            p.setDoi(clean);
            p.setTitle(msg.path("title").isArray() && msg.path("title").size() > 0
                    ? msg.path("title").get(0).asText() : "");
            if (msg.path("published-print").path("date-parts").isArray()
                    && msg.path("published-print").path("date-parts").size() > 0) {
                JsonNode dp = msg.path("published-print").path("date-parts").get(0);
                if (dp.isArray() && dp.size() > 0) p.setYear(dp.get(0).asInt());
            }
            p.setUrl("https://doi.org/" + clean);
            p.setId("doi:" + clean);
            List<String> authors = new ArrayList<>();
            for (JsonNode a : msg.path("author")) {
                String name = (a.path("given").asText("") + " " + a.path("family").asText("")).trim();
                if (!name.isBlank()) authors.add(name);
            }
            p.setAuthors(authors);
            return p.hasStableId() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public LitPaper resolveArxiv(String arxivId) {
        if (arxivId == null || arxivId.isBlank()) return null;
        String id = arxivId.trim().replace("arxiv:", "").replace("https://arxiv.org/abs/", "");
        try {
            String url = "https://export.arxiv.org/api/query?id_list=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            String xml = getText(url, Map.of());
            LitPaper p = parseArxivEntry(xml);
            if (p != null) {
                p.setArxivId(id);
                p.setId("arxiv:" + id);
                p.setSource("arxiv");
                p.setUrl("https://arxiv.org/abs/" + id);
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private List<LitPaper> searchSemanticScholar(String query, int limit) {
        List<LitPaper> out = new ArrayList<>();
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.semanticscholar.org/graph/v1/paper/search?query=" + q
                    + "&limit=" + limit
                    + "&fields=title,abstract,year,venue,externalIds,authors,url,openAccessPdf,citationCount,tldr";
            Map<String, String> headers = new LinkedHashMap<>();
            if (s2ApiKey != null && !s2ApiKey.isBlank()) {
                headers.put("x-api-key", s2ApiKey.trim());
            }
            JsonNode root = getJson(url, headers);
            for (JsonNode n : root.path("data")) {
                LitPaper p = fromS2(n);
                if (p != null && p.hasStableId() && yearOk(p)) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<LitPaper> searchOpenAlex(String query, int limit) {
        List<LitPaper> out = new ArrayList<>();
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.openalex.org/works?search=" + q
                    + "&per_page=" + limit
                    + "&filter=from_publication_date:" + effectiveMinYear() + "-01-01"
                    + "&mailto=ekina@local";
            JsonNode root = getJson(url, Map.of("User-Agent", "EkinaPaperBot/1.0 (mailto:ekina@local)"));
            for (JsonNode n : root.path("results")) {
                LitPaper p = fromOpenAlex(n);
                if (p != null && p.hasStableId() && yearOk(p)) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<LitPaper> searchArxiv(String query, int limit) {
        List<LitPaper> out = new ArrayList<>();
        try {
            String q = URLEncoder.encode("all:" + query, StandardCharsets.UTF_8);
            String url = "https://export.arxiv.org/api/query?search_query=" + q
                    + "&start=0&max_results=" + limit;
            String xml = getText(url, Map.of());
            // split entries crudely
            String[] parts = xml.split("<entry>");
            for (int i = 1; i < parts.length; i++) {
                LitPaper p = parseArxivEntry("<entry>" + parts[i]);
                if (p != null && p.hasStableId() && yearOk(p)) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private LitPaper fromS2(JsonNode n) {
        LitPaper p = new LitPaper();
        p.setSource("semantic_scholar");
        p.setTitle(n.path("title").asText(""));
        p.setAbstractText(n.path("abstract").asText(null));
        if (n.hasNonNull("year")) p.setYear(n.path("year").asInt());
        p.setVenue(n.path("venue").asText(null));
        applyVenueTier(p);
        p.setUrl(n.path("url").asText(null));
        if (n.hasNonNull("citationCount")) p.setCitationCount(n.path("citationCount").asInt());
        JsonNode tldr = n.path("tldr");
        if (tldr.hasNonNull("text")) p.setTldr(tldr.path("text").asText());
        JsonNode oap = n.path("openAccessPdf");
        if (oap.hasNonNull("url")) p.setPdfUrl(oap.path("url").asText());
        JsonNode ext = n.path("externalIds");
        if (ext.hasNonNull("DOI")) p.setDoi(ext.path("DOI").asText());
        if (ext.hasNonNull("ArXiv")) p.setArxivId(ext.path("ArXiv").asText());
        List<String> authors = new ArrayList<>();
        for (JsonNode a : n.path("authors")) {
            String name = a.path("name").asText("");
            if (!name.isBlank()) authors.add(name);
        }
        p.setAuthors(authors);
        String id = n.path("paperId").asText("");
        p.setId(id.isBlank() ? keyOf(p) : "s2:" + id);
        return p;
    }

    private LitPaper fromOpenAlex(JsonNode n) {
        LitPaper p = new LitPaper();
        p.setSource("openalex");
        p.setTitle(n.path("title").asText(""));
        p.setAbstractText(null);
        String doi = n.path("doi").asText("");
        if (doi.startsWith("https://doi.org/")) doi = doi.substring("https://doi.org/".length());
        if (!doi.isBlank()) p.setDoi(doi);
        String y = n.path("publication_year").asText("");
        if (!y.isBlank()) {
            try { p.setYear(Integer.parseInt(y)); } catch (Exception ignored) {}
        }
        p.setVenue(n.path("primary_location").path("source").path("display_name").asText(null));
        applyVenueTier(p);
        p.setUrl(n.path("id").asText(null));
        // arxiv from ids
        for (JsonNode loc : n.path("locations")) {
            String land = loc.path("landing_page_url").asText("");
            if (land.contains("arxiv.org/abs/")) {
                p.setArxivId(land.replaceAll(".*arxiv\\.org/abs/", "").replaceAll("[?#].*", ""));
            }
        }
        List<String> authors = new ArrayList<>();
        for (JsonNode a : n.path("authorships")) {
            String name = a.path("author").path("display_name").asText("");
            if (!name.isBlank()) authors.add(name);
        }
        p.setAuthors(authors);
        p.setId(n.path("id").asText(keyOf(p)));
        return p;
    }

    private LitPaper parseArxivEntry(String entryXml) {
        LitPaper p = new LitPaper();
        p.setSource("arxiv");
        p.setTitle(xmlTag(entryXml, "title").replaceAll("\\s+", " ").trim());
        p.setAbstractText(xmlTag(entryXml, "summary").replaceAll("\\s+", " ").trim());
        String idUrl = xmlTag(entryXml, "id");
        if (idUrl.contains("arxiv.org/abs/")) {
            String id = idUrl.replaceAll(".*arxiv\\.org/abs/", "").trim();
            p.setArxivId(id);
            p.setId("arxiv:" + id);
            p.setUrl("https://arxiv.org/abs/" + id);
        }
        String published = xmlTag(entryXml, "published");
        if (published.length() >= 4) {
            try { p.setYear(Integer.parseInt(published.substring(0, 4))); } catch (Exception ignored) {}
        }
        List<String> authors = new ArrayList<>();
        int idx = 0;
        while (true) {
            int a = entryXml.indexOf("<author>", idx);
            if (a < 0) break;
            int b = entryXml.indexOf("</author>", a);
            if (b < 0) break;
            String block = entryXml.substring(a, b);
            String name = xmlTag(block, "name");
            if (!name.isBlank()) authors.add(name.trim());
            idx = b + 1;
        }
        p.setAuthors(authors);
        p.setVenue("arXiv");
        return p.getTitle() == null || p.getTitle().isBlank() ? null : p;
    }

    private static String xmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int a = xml.indexOf(open);
        int b = xml.indexOf(close);
        if (a < 0 || b < 0 || b <= a) return "";
        return xml.substring(a + open.length(), b).trim();
    }

    private boolean yearOk(LitPaper p) {
        if (p.getYear() == null) return true;
        return p.getYear() >= effectiveMinYear() - 1; // allow slight older for coverage
    }

    private void putUnique(Map<String, LitPaper> map, LitPaper p) {
        if (p == null || !p.hasStableId()) return;
        String k = keyOf(p);
        map.putIfAbsent(k, p);
    }

    private static String keyOf(LitPaper p) {
        if (p.getDoi() != null && !p.getDoi().isBlank()) {
            return "doi:" + p.getDoi().toLowerCase(Locale.ROOT);
        }
        if (p.getArxivId() != null && !p.getArxivId().isBlank()) {
            return "arxiv:" + p.getArxivId().toLowerCase(Locale.ROOT);
        }
        return "t:" + String.valueOf(p.getTitle()).toLowerCase(Locale.ROOT);
    }

    private JsonNode getJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .GET();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        }
        return mapper.readTree(resp.body());
    }

    private String getText(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .GET();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }
}
