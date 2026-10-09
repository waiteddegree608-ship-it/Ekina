package com.ekina.application.contact;

import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emotion / bond state for 代理人 — facade vs inner affection.
 * Default: already in love, long-term Iron Blood elite mask.
 */
@Service
public class AgentEmotionService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> card = new LinkedHashMap<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @PostConstruct
    public void init() {
        load();
        if (card.isEmpty()) {
            seedDefaults();
            save();
        } else {
            card.remove("modelCode");
            card.putIfAbsent("bondStage", "already_in_love_masking");
            card.putIfAbsent("facade", "prim_proper");
            card.putIfAbsent("mode", "work");
            // migrate cringe defaults from earlier seed
            Object mood = card.get("surfaceMood");
            if (mood != null && String.valueOf(mood).contains("冷淡")) {
                card.put("surfaceMood", "正经待命");
                card.put("surfaceAttitude", "敬语、高效、一板一眼");
                card.put("facade", "prim_proper");
            }
            save();
        }
    }

    private void seedDefaults() {
        // 假正经：上班一板一眼，私下老不正经；已深爱，傲娇不直说
        card.put("bondStage", "already_in_love_masking");
        card.put("facade", "prim_proper"); // 假正经
        card.put("mode", "work"); // work | off_duty
        card.put("surfaceMood", "正经待命");
        card.put("surfaceAttitude", "敬语、高效、一板一眼");
        card.put("innerAffection", 92);
        card.put("innerTenderness", 78);
        card.put("irritation", 12);
        card.put("possessiveness", 60);
        card.put("trust", 90);
        card.put("maskIntegrity", 80); // 被关心时下降＝傲娇破功
        card.put("lastTrigger", "初始：已爱上主人；工作装正经，私下可放得开");
        card.put("backstoryNote", "曾是敌方二把手，战败后成为战俘；由厌烦转为深爱。对外假正经，对内很贪主人亲近。");
        card.put("updatedAt", Instant.now().toString());
    }

    public synchronized Map<String, Object> get() {
        return new LinkedHashMap<>(card);
    }

    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                if ("immutable".equals(e.getKey()) || "backstoryNote".equals(e.getKey())) continue;
                Object v = e.getValue();
                if (isGauge(e.getKey())) {
                    card.put(e.getKey(), clampInt(v, 0, 100));
                } else {
                    card.put(e.getKey(), v);
                }
            }
        }
        card.put("updatedAt", Instant.now().toString());
        save();
        return get();
    }

    public String surfaceLine() {
        Object mood = card.get("surfaceMood");
        Object att = card.get("surfaceAttitude");
        return (mood == null ? "冷淡" : mood) + " · " + (att == null ? "听令" : att);
    }

    public String asPromptBlock() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n[代理人情感与相处节奏 — 必须遵守；勿向主人朗读数值]\n");
            sb.append("已定稿：曾是敌方二把手→战俘→厌烦→现已深爱。不要重演战俘初期长剧。\n");
            sb.append("人设关键词：**假正经**。工作=一板一眼敬语高效；主人熬夜要正经提醒休息；");
            sb.append("被关心时傲娇自称铁血精英，其实很吃这套、暗自期待亲近；");
            sb.append("工作结束后可老不正经、私下玩得开；一旦有工作指令立刻切换回完全正经。\n");
            sb.append("当前内部状态（仅供你把握语气，禁止在回复里报进度条）：\n");
            sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(card)).append("\n");
            sb.append("mode=work 时保持上班脸；mode=off_duty 或主人明确收工/要亲密时才放开。");
            sb.append("可用 updateAgentEmotion 改 mode / maskIntegrity / lastTrigger 等。\n");
            return sb.toString();
        } catch (Exception e) {
            return "\n[代理人情感] " + card + "\n";
        }
    }

    /** Short public signature for IM contact list — plain, not edgy. */
    public String contactSignature() {
        Object mode = card.get("mode");
        if ("off_duty".equals(String.valueOf(mode))) {
            return "下班了";
        }
        return "请吩咐";
    }

    private static boolean isGauge(String key) {
        return switch (key) {
            case "innerAffection", "innerTenderness", "irritation",
                    "possessiveness", "trust", "maskIntegrity" -> true;
            default -> false;
        };
    }

    private static int clampInt(Object v, int lo, int hi) {
        int n;
        if (v instanceof Number num) n = num.intValue();
        else {
            try {
                n = Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception e) {
                n = lo;
            }
        }
        return Math.max(lo, Math.min(hi, n));
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("agent_emotion.json");
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            Map<String, Object> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            card.clear();
            if (loaded != null) card.putAll(loaded);
        } catch (Exception e) {
            System.err.println("[AgentEmotion] load failed: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(card),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[AgentEmotion] save failed: " + e.getMessage());
        }
    }
}
