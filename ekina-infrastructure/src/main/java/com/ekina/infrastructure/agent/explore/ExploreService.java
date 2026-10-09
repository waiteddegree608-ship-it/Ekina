package com.ekina.infrastructure.agent.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free, keyless exploration: Bilibili hot/search/bangumi + DuckDuckGo HTML fallback.
 */
@Service
public class ExploreService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest = RestClient.builder()
            .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .defaultHeader("Referer", "https://www.bilibili.com")
            .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build();

    public String explore(String mode, String query) {
        String m = mode == null ? "hot" : mode.trim().toLowerCase();
        int year = LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear();
        String q = withCurrentYear(query, year);
        StringBuilder header = new StringBuilder();
        header.append("【抓取时钟】Asia/Shanghai 今天=").append(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                .append(" 年份=").append(year)
                .append("（回答时不要用更早年份当「现在」）\n\n");
        try {
            String body = switch (m) {
                case "hot", "popular", "rank", "ranking" -> formatList("B站实时热门（高播放/综合热度）", bilibiliPopular());
                case "search", "video" -> formatList("B站搜索:" + q, bilibiliSearch(q == null || q.isBlank() ? "热门" : q));
                case "bangumi", "anime", "番剧", "新番" -> bilibiliBangumiReport();
                case "meme", "梗", "hotword", "热梗" -> formatList("热搜/热梗线索（优先本年）", bilibiliHotWords(q));
                case "web", "news" -> webSearchFree(q == null || q.isBlank() ? (year + " 今日热点") : q);
                default -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(formatList("B站实时热门", bilibiliPopular())).append("\n\n");
                    sb.append(bilibiliBangumiReport()).append("\n\n");
                    sb.append(formatList("热搜/热梗", bilibiliHotWords(q))).append("\n\n");
                    if (q != null && !q.isBlank()) {
                        sb.append(formatList("B站搜索:" + q, bilibiliSearch(q))).append("\n\n");
                        sb.append(webSearchFree(q));
                    } else {
                        sb.append(webSearchFree(year + " 网络热梗 本周"));
                    }
                    yield sb.toString();
                }
            };
            return header + body;
        } catch (Exception e) {
            return header + "explore failed (" + m + "): " + e.getMessage();
        }
    }

    /** Ensure meme/news queries mention the current calendar year unless already dated. */
    private static String withCurrentYear(String query, int year) {
        if (query == null || query.isBlank()) {
            return query;
        }
        if (query.matches(".*20\\d{2}.*")) {
            return query;
        }
        // model often wrongly uses 2025 — force clock year for 梗/热门/新闻类
        if (query.contains("梗") || query.contains("热搜") || query.contains("热点")
                || query.contains("新闻") || query.contains("最近") || query.contains("本周")) {
            return year + "年 " + query;
        }
        return query;
    }

    private String bilibiliBangumiReport() throws Exception {
        StringBuilder sb = new StringBuilder();
        List<Map<String, String>> timeline = bilibiliTimeline();
        List<Map<String, String>> hot = bilibiliBangumiRank();
        if (!timeline.isEmpty()) {
            sb.append(formatList("本周/近日放送（时间表，偏「最近更新」）", timeline)).append("\n");
        }
        if (!hot.isEmpty()) {
            sb.append(formatList("高热度/高分追番榜（不一定是新开播）", hot)).append("\n");
            sb.append("说明：主人若说「最近热门番」通常指高热度追番；若说「新番/本季新开」请优先看时间表。\n");
        }
        if (sb.isEmpty()) {
            sb.append(formatList("番剧搜索兜底", bilibiliSearch(LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear() + " 新番")));
        }
        return sb.toString();
    }

    public List<Map<String, String>> bilibiliPopular() throws Exception {
        // popular feed
        String json = get("https://api.bilibili.com/x/web-interface/popular?ps=15&pn=1");
        JsonNode root = mapper.readTree(json);
        if (root.path("code").asInt(-1) != 0) {
            // ranking fallback
            json = get("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all");
            root = mapper.readTree(json);
        }
        List<Map<String, String>> items = new ArrayList<>();
        JsonNode list = root.path("data").path("list");
        if (!list.isArray()) {
            list = root.path("data").path("item");
        }
        if (list.isArray()) {
            int i = 0;
            for (JsonNode n : list) {
                if (i++ >= 12) break;
                Map<String, String> m = new LinkedHashMap<>();
                String bvid = text(n, "bvid");
                m.put("title", strip(text(n, "title")));
                m.put("author", firstNonBlank(text(n.path("owner"), "name"), text(n, "author")));
                m.put("bvid", bvid);
                m.put("url", bvid.isBlank() ? "" : "https://www.bilibili.com/video/" + bvid);
                m.put("desc", strip(firstNonBlank(text(n, "desc"), text(n, "description"))));
                long play = n.path("stat").path("view").asLong(n.path("play").asLong(0));
                m.put("play", String.valueOf(play));
                items.add(m);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("empty popular list code=" + root.path("code").asText());
        }
        return items;
    }

    public List<Map<String, String>> bilibiliSearch(String keyword) throws Exception {
        String q = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        String json = get("https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&page_size=10&keyword=" + q);
        JsonNode root = mapper.readTree(json);
        List<Map<String, String>> items = new ArrayList<>();
        JsonNode results = root.path("data").path("result");
        if (results.isArray()) {
            int i = 0;
            for (JsonNode n : results) {
                if (i++ >= 10) break;
                Map<String, String> m = new LinkedHashMap<>();
                String bvid = text(n, "bvid");
                m.put("title", strip(text(n, "title")));
                m.put("author", text(n, "author"));
                m.put("bvid", bvid);
                m.put("url", bvid.isBlank() ? "" : "https://www.bilibili.com/video/" + bvid);
                m.put("desc", strip(text(n, "description")));
                m.put("play", text(n, "play"));
                items.add(m);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("search empty code=" + root.path("code").asText() + " msg=" + root.path("message").asText());
        }
        return items;
    }

    public List<Map<String, String>> bilibiliTimeline() throws Exception {
        List<Map<String, String>> items = new ArrayList<>();
        String json = get("https://api.bilibili.com/pgc/web/timeline/v2?day_before=3&day_after=3");
        JsonNode root = mapper.readTree(json);
        JsonNode result = root.path("result");
        if (!result.isArray()) {
            return items;
        }
        for (JsonNode day : result) {
            String date = text(day, "date");
            JsonNode episodes = day.path("episodes");
            if (!episodes.isArray()) continue;
            for (JsonNode ep : episodes) {
                Map<String, String> m = new LinkedHashMap<>();
                String title = strip(firstNonBlank(text(ep, "title"), text(ep, "long_title")));
                m.put("title", title);
                m.put("author", strip(text(ep, "published")));
                m.put("desc", "放送日 " + date + " " + strip(text(ep, "pub_index")));
                m.put("url", firstNonBlank(text(ep, "url"), "https://www.bilibili.com/anime/"));
                m.put("bvid", text(ep, "episode_id"));
                items.add(m);
                if (items.size() >= 15) return items;
            }
        }
        return items;
    }

    public List<Map<String, String>> bilibiliBangumiRank() throws Exception {
        List<Map<String, String>> items = new ArrayList<>();
        String json = get("https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=1");
        JsonNode root = mapper.readTree(json);
        JsonNode list = root.path("data").path("list");
        if (!list.isArray()) return items;
        int i = 0;
        for (JsonNode n : list) {
            if (i++ >= 12) break;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("title", strip(text(n, "title")));
            m.put("author", text(n, "rating") + "分");
            m.put("desc", strip(String.valueOf(n.path("new_ep"))));
            m.put("url", text(n, "url"));
            m.put("bvid", text(n, "season_id"));
            items.add(m);
        }
        return items;
    }

    public List<Map<String, String>> bilibiliHotWords(String extra) throws Exception {
        List<Map<String, String>> items = new ArrayList<>();
        int year = LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear();
        // square API often 412; try popular search terms via search + popular titles as "热词"
        try {
            String json = get("https://api.bilibili.com/x/web-interface/search/square?limit=20");
            if (json != null && json.trim().startsWith("{")) {
                JsonNode root = mapper.readTree(json);
                JsonNode trending = root.path("data").path("trending").path("list");
                if (trending.isArray()) {
                    for (JsonNode n : trending) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("title", strip(text(n, "show_name")));
                        m.put("desc", "B站热搜");
                        m.put("url", "https://search.bilibili.com/all?keyword="
                                + URLEncoder.encode(m.get("title"), StandardCharsets.UTF_8));
                        items.add(m);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Derive "热梗线索" from current hot video titles (always works if popular API works)
        if (items.size() < 5) {
            try {
                for (Map<String, String> v : bilibiliPopular()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("title", v.get("title"));
                    m.put("desc", "来自实时热门视频标题（可当热梗线索）");
                    m.put("url", v.get("url"));
                    m.put("author", v.get("author"));
                    items.add(m);
                    if (items.size() >= 12) break;
                }
            } catch (Exception ignored) {
            }
        }
        String memeQ = (extra == null || extra.isBlank())
                ? (year + " 热梗 解释")
                : withCurrentYear(extra, year);
        try {
            items.addAll(bilibiliSearch(memeQ));
        } catch (Exception ignored) {
        }
        return items;
    }

    public String webSearchFree(String query) {
        int year = LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear();
        String q = withCurrentYear(query == null ? "" : query.trim(), year);
        if (q.isEmpty()) return "empty query";
        StringBuilder sb = new StringBuilder();
        sb.append("=== Web (DuckDuckGo HTML) : ").append(q).append(" ===\n");
        sb.append("（查询已按本地时钟年份=").append(year).append(" 校正）\n");
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36")
                    .timeout(15000)
                    .get();
            Elements results = doc.select("div.result, div.results_links");
            int i = 0;
            for (Element el : results) {
                if (i >= 8) break;
                Element a = el.selectFirst("a.result__a, a.result__url, a");
                Element snip = el.selectFirst("a.result__snippet, td.result-snippet, .result__snippet");
                if (a == null) continue;
                String title = a.text();
                String href = a.absUrl("href");
                if (title.isBlank()) continue;
                i++;
                sb.append(i).append(". ").append(title).append("\n");
                if (href != null && !href.isBlank()) sb.append("   ").append(href).append("\n");
                if (snip != null) sb.append("   ").append(snip.text()).append("\n");
            }
            if (i == 0) {
                // DDG instant answer API as second try
                String ia = get("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8));
                JsonNode root = mapper.readTree(ia);
                String abs = root.path("AbstractText").asText("");
                String topic = root.path("Heading").asText("");
                if (!abs.isBlank()) {
                    sb.append("摘要: ").append(topic).append(" — ").append(abs).append("\n");
                } else {
                    sb.append("(未解析到结果，可换关键词或用 exploreBilibili)\n");
                }
            }
        } catch (Exception e) {
            sb.append("web search failed: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private String formatList(String head, List<Map<String, String>> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(head).append(" ===\n");
        if (items == null || items.isEmpty()) {
            sb.append("(empty)\n");
            return sb.toString();
        }
        int i = 1;
        for (Map<String, String> m : items) {
            sb.append(i++).append(". ").append(nullToEmpty(m.get("title"))).append("\n");
            if (m.get("author") != null && !m.get("author").isBlank()) {
                sb.append("   UP/信息: ").append(m.get("author")).append("\n");
            }
            if (m.get("play") != null && !m.get("play").isBlank() && !"0".equals(m.get("play"))) {
                sb.append("   播放: ").append(m.get("play")).append("\n");
            }
            if (m.get("url") != null && !m.get("url").isBlank()) {
                sb.append("   链接: ").append(m.get("url")).append("\n");
            }
            if (m.get("desc") != null && !m.get("desc").isBlank()) {
                String d = m.get("desc");
                if (d.length() > 120) d = d.substring(0, 120) + "...";
                sb.append("   简介: ").append(d).append("\n");
            }
        }
        sb.append("\n说明: 以上为实时抓取的公开元数据；若要完整看某条视频请对具体 BV 链接调用 watchVideo。\n");
        return sb.toString();
    }

    private String get(String url) {
        return rest.get().uri(url).retrieve().body(String.class);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("\"", "\"").trim();
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
