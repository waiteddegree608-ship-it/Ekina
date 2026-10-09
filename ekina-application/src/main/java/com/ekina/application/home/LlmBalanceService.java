package com.ekina.application.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live provider balances for the capability board.
 * DeepSeek: official GET /user/balance (CNY/USD wallet).
 * OpenCode Go: GET /zen/go/v1/usage (rolling/weekly/monthly percent used).
 */
@Service
public class LlmBalanceService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();
    private Map<String, Object> cached;
    private long cachedAtMs;

    @Value("${spring.ai.openai.api-key:}")
    private String opencodeKey;

    @Value("${spring.ai.openai.base-url:https://opencode.ai/zen/go/v1}")
    private String opencodeBase;

    @Value("${ekina.deepseek.api-key:}")
    private String deepseekKey;

    @Value("${ekina.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBase;

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            if (cached != null && System.currentTimeMillis() - cachedAtMs < 45_000) {
                return cached;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fetchedAt", Instant.now().toString());
            out.put("deepseek", fetchDeepseek());
            out.put("opencode", fetchOpencode());
            cached = out;
            cachedAtMs = System.currentTimeMillis();
            return out;
        }
    }

    public Map<String, Object> refresh() {
        synchronized (lock) {
            cached = null;
            cachedAtMs = 0;
        }
        return snapshot();
    }

    private Map<String, Object> fetchDeepseek() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "deepseek");
        row.put("label", "DeepSeek 官方");
        if (blank(deepseekKey)) {
            row.put("ok", false);
            row.put("error", "未配置 DEEPSEEK_API_KEY");
            return row;
        }
        try {
            JsonNode n = getJson(trimSlash(deepseekBase) + "/user/balance", deepseekKey);
            boolean available = n.path("is_available").asBoolean(false);
            JsonNode infos = n.path("balance_infos");
            JsonNode pick = null;
            if (infos.isArray()) {
                for (JsonNode info : infos) {
                    if ("CNY".equalsIgnoreCase(info.path("currency").asText())) {
                        pick = info;
                        break;
                    }
                }
                if (pick == null && infos.size() > 0) pick = infos.get(0);
            }
            row.put("ok", true);
            row.put("available", available);
            if (pick != null) {
                row.put("currency", pick.path("currency").asText("CNY"));
                row.put("total", pick.path("total_balance").asText("0"));
                row.put("granted", pick.path("granted_balance").asText("0"));
                row.put("toppedUp", pick.path("topped_up_balance").asText("0"));
            }
            row.put("hint", available ? "余额够用" : "余额不足，API 可能拒答");
            return row;
        } catch (Exception e) {
            row.put("ok", false);
            row.put("error", clip(e.getMessage()));
            return row;
        }
    }

    private Map<String, Object> fetchOpencode() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "opencode");
        row.put("label", "OpenCode Go");
        if (blank(opencodeKey)) {
            row.put("ok", false);
            row.put("error", "未配置 OPENCODE_API_KEY");
            return row;
        }
        try {
            String usageUrl = usageUrl(opencodeBase);
            JsonNode n = getJson(usageUrl, opencodeKey);
            JsonNode usage = n.path("usage");
            if (!usage.isObject()) {
                row.put("ok", false);
                row.put("error", "用量接口无 usage 字段");
                row.put("hint", "OpenCode 钱包余额没有官方 API，目前只能看 Go 套餐窗口用量。");
                return row;
            }
            Map<String, Object> windows = new LinkedHashMap<>();
            int hottest = 0;
            String hottestName = "";
            for (String name : new String[]{"rolling", "weekly", "monthly"}) {
                JsonNode w = usage.path(name);
                if (!w.isObject()) continue;
                int pct = w.path("percent").asInt(0);
                Map<String, Object> win = new LinkedHashMap<>();
                win.put("status", w.path("status").asText("ok"));
                win.put("percent", pct);
                win.put("remainingPercent", Math.max(0, 100 - pct));
                win.put("resetsAt", w.path("resetsAt").asText(""));
                windows.put(name, win);
                if (pct >= hottest) {
                    hottest = pct;
                    hottestName = name;
                }
            }
            row.put("ok", true);
            row.put("windows", windows);
            row.put("hottestWindow", hottestName);
            row.put("hottestPercent", hottest);
            row.put("hint", "Go 套餐窗口已用量（不是钱包美元）。官方暂无余额 API。");
            return row;
        } catch (Exception e) {
            row.put("ok", false);
            row.put("error", clip(e.getMessage()));
            return row;
        }
    }

    private JsonNode getJson(String url, String key) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + clip(resp.body()));
        }
        return mapper.readTree(resp.body() == null ? "{}" : resp.body());
    }

    private static String usageUrl(String base) {
        String b = trimSlash(base == null ? "" : base);
        if (b.contains("/zen/go/v1")) {
            return b.replaceFirst("/v1$", "") + "/v1/usage";
        }
        if (b.endsWith("/v1")) {
            return "https://opencode.ai/zen/go/v1/usage";
        }
        return "https://opencode.ai/zen/go/v1/usage";
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replaceAll("sk-[A-Za-z0-9]+", "sk-***");
        return t.length() > 180 ? t.substring(0, 180) : t;
    }
}
