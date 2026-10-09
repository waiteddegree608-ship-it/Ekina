package com.ekina.application.state;

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
 * Mutable working-memory / persona state card the agent may update herself.
 */
@Service
public class StateCardService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> card = new LinkedHashMap<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @PostConstruct
    public void init() {
        load();
        if (card.isEmpty()) {
            card.put("mood", "平静偏黏人");
            card.put("focus", "陪主人聊天");
            card.put("open_loops", new java.util.ArrayList<String>());
            card.put("interests", java.util.List.of("AI", "番剧", "热梗", "LoRA"));
            card.put("last_explore", "");
            card.put("updatedAt", Instant.now().toString());
            save();
        }
    }

    public synchronized Map<String, Object> get() {
        return new LinkedHashMap<>(card);
    }

    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                // prevent wiping identity keys by accident
                if ("immutable".equals(e.getKey())) continue;
                card.put(e.getKey(), e.getValue());
            }
        }
        card.put("updatedAt", Instant.now().toString());
        save();
        return get();
    }

    public synchronized Map<String, Object> setField(String key, Object value) {
        if (key != null && !key.isBlank()) {
            card.put(key, value);
            card.put("updatedAt", Instant.now().toString());
            save();
        }
        return get();
    }

    public String asPromptBlock() {
        try {
            return "\n[State Card 当前状态 — 可被姬娜用 updateStateCard 更新]\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(card) + "\n";
        } catch (Exception e) {
            return "\n[State Card] " + card + "\n";
        }
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("state_card.json");
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
            System.err.println("[StateCard] load failed: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(card), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[StateCard] save failed: " + e.getMessage());
        }
    }
}
