package com.ekina.application.life;

import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight autonomous life loop: diary notes + optional idle Bilibili discovery.
 * Heavy watchVideo is NOT auto-run (cost); only metadata + channel note.
 */
@Service
public class LifeLoopService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest = RestClient.builder().build();
    private final Set<String> seenBv = new HashSet<>();

    @Autowired
    private ChannelService channelService;

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Value("${ekina.life.enabled:true}")
    private boolean enabled;

    @Value("${ekina.life.idle-bilibili:true}")
    private boolean idleBilibili;

    @Value("${ekina.life.interests:AI,LoRA,编程,机器学习,热梗,新番,番剧}")
    private String interestsCsv;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.life.notify-user:false}")
    private boolean notifyUser;

    @Scheduled(fixedDelayString = "${ekina.life.heartbeat-ms:1200000}")
    public void heartbeat() {
        if (!enabled) {
            return;
        }
        try {
            String interest = pickInterest();
            Map<String, Object> actionLog = new HashMap<>();
            actionLog.put("ts", Instant.now().toString());
            actionLog.put("intent", idleBilibili ? "explore_bilibili" : "diary");
            actionLog.put("interest", interest);

            if (idleBilibili) {
                Map<String, Object> clip = searchBilibili(interest);
                actionLog.put("bilibili", clip);
                writeDiary(interest, clip);
                if (notifyUser && clip.get("bvid") != null) {
                    String text = "主人去忙的时候，姬娜刷到了「" + clip.get("title")
                            + "」( " + clip.get("bvid") + " )。只记了标题简介，没有自动完整观看哦～ 想让姬娜认真看可以说一声。";
                    channelService.append(defaultUser, "agent", "chat", text, clip, "done");
                }
            } else {
                writeDiary(interest, Map.of("note", "idle heartbeat"));
            }

            Path log = homeQuotaService.homePath().resolve("state").resolve("actions.jsonl");
            Files.createDirectories(log.getParent());
            Files.writeString(log, mapper.writeValueAsString(actionLog) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            System.out.println("[LifeLoop] heartbeat ok interest=" + interest);
        } catch (Exception e) {
            System.err.println("[LifeLoop] heartbeat failed: " + e.getMessage());
        }
    }

    public Map<String, Object> runOnce() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("ts", Instant.now().toString());
        try {
            String interest = pickInterest();
            out.put("interest", interest);
            Map<String, Object> clip = searchBilibili(interest);
            out.put("bilibili", clip);
            writeDiary(interest, clip);
            Path log = homeQuotaService.homePath().resolve("state").resolve("actions.jsonl");
            Files.createDirectories(log.getParent());
            Map<String, Object> actionLog = new HashMap<>();
            actionLog.put("ts", Instant.now().toString());
            actionLog.put("intent", "manual_runOnce");
            actionLog.put("interest", interest);
            actionLog.put("bilibili", clip);
            Files.writeString(log, mapper.writeValueAsString(actionLog) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            if (clip.get("bvid") != null) {
                out.put("hint", "Found video metadata. For full multimodal watch use watchVideo on the BV url.");
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private String pickInterest() {
        String[] parts = interestsCsv.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) list.add(p.trim());
        }
        if (list.isEmpty()) return "AI";
        return list.get((int) (System.currentTimeMillis() / 600_000 % list.size()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> searchBilibili(String keyword) {
        Map<String, Object> out = new HashMap<>();
        out.put("keyword", keyword);
        try {
            String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&page=1&page_size=5&keyword="
                    + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            // public search (non-wbi) fallback
            url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&page_size=8&keyword="
                    + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String json = rest.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
                    .header("Referer", "https://www.bilibili.com")
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json);
            if (root.path("code").asInt(-1) != 0) {
                out.put("error", root.path("message").asText("search failed"));
                return out;
            }
            JsonNode results = root.path("data").path("result");
            if (!results.isArray()) {
                out.put("error", "no results");
                return out;
            }
            for (JsonNode item : results) {
                String bvid = item.path("bvid").asText("");
                if (bvid.isBlank() || seenBv.contains(bvid)) continue;
                seenBv.add(bvid);
                if (seenBv.size() > 500) {
                    seenBv.clear();
                }
                out.put("bvid", bvid);
                out.put("title", stripHtml(item.path("title").asText("")));
                out.put("author", item.path("author").asText(""));
                out.put("description", stripHtml(item.path("description").asText("")));
                out.put("url", "https://www.bilibili.com/video/" + bvid);
                out.put("play", item.path("play").asLong(0));
                break;
            }
            if (!out.containsKey("bvid")) {
                out.put("error", "all results already seen");
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private void writeDiary(String interest, Map<String, Object> clip) throws Exception {
        String day = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path diary = homeQuotaService.homePath().resolve("diary").resolve(day + ".md");
        Files.createDirectories(diary.getParent());
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(diary)) {
            sb.append("# ").append(day).append(" 日记\n\n");
        }
        sb.append("## ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                .append("\n");
        sb.append("- mood: 平静偏好奇\n");
        sb.append("- interest: ").append(interest).append("\n");
        if (clip.get("bvid") != null) {
            sb.append("- bilibili: ").append(clip.get("title")).append(" (").append(clip.get("bvid")).append(")\n");
            sb.append("- note: 只做了发现与简介记录，未自动完整观看。\n");
        } else {
            sb.append("- note: 心跳巡视，没有新的好看片子。\n");
            if (clip.get("error") != null) {
                sb.append("- error: ").append(clip.get("error")).append("\n");
            }
        }
        sb.append("\n");
        Files.writeString(diary, sb.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
