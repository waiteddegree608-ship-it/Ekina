package com.ekina.application.prefs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persist shell UI prefs across app restarts (theme, emoji, etc.). */
@Service
public class UiPrefsService {

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path file;

    @PostConstruct
    void init() {
        file = Path.of(agentHome).toAbsolutePath().normalize().resolve("ui-prefs.json");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file)) {
                save(defaults());
            }
        } catch (Exception e) {
            System.err.println("[UiPrefs] init failed: " + e.getMessage());
        }
    }

    public Map<String, Object> get() {
        try {
            if (!Files.isRegularFile(file)) return defaults();
            Map<String, Object> raw = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> out = defaults();
            if (raw != null) out.putAll(raw);
            return out;
        } catch (Exception e) {
            return defaults();
        }
    }

    public Map<String, Object> put(Map<String, Object> patch) {
        Map<String, Object> cur = get();
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                cur.put(e.getKey(), e.getValue());
            }
        }
        save(cur);
        return cur;
    }

    private void save(Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[UiPrefs] save failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("theme", "paper");
        m.put("noEmoji", false);
        m.put("crewCollapsed", false);
        return m;
    }
}
