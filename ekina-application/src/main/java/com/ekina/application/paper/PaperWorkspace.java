package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates and maintains the paper workspace contract under AgentHome/papers/&lt;id&gt;/.
 */
@Component
public class PaperWorkspace {

    private final ObjectMapper mapper = new ObjectMapper();

    public Path init(Path papersRoot, PaperProject project) throws Exception {
        Path root = papersRoot.resolve(project.getId());
        Files.createDirectories(root);
        for (String sub : List.of(
                "lit", "code", "runs", "figures", "tex", "outbox", "notes", "repro")) {
            Files.createDirectories(root.resolve(sub));
        }
        project.setRootPath(root.toAbsolutePath().toString());
        writeJson(root.resolve("project.json"), project);
        writeArtifacts(root, "INIT", Map.of(
                "status", "ok",
                "createdAt", Instant.now().toString(),
                "dirs", List.of("lit", "code", "runs", "figures", "tex", "outbox", "notes", "repro")
        ));
        Files.writeString(root.resolve("diary.md"),
                "# Paper " + project.getId() + "\n\n- created: " + Instant.now() + "\n",
                StandardCharsets.UTF_8);
        return root;
    }

    public Path rootOf(PaperProject project) {
        return Path.of(project.getRootPath());
    }

    public void writeArtifacts(Path root, String stage, Map<String, Object> payload) throws Exception {
        Path f = root.resolve("ARTIFACTS.json");
        Map<String, Object> all = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            if (existing != null) all.putAll(existing);
        }
        Map<String, Object> entry = new LinkedHashMap<>(payload);
        entry.put("updatedAt", Instant.now().toString());
        all.put(stage, entry);
        writeJson(f, all);
    }

    public void appendDiary(Path root, String line) {
        try {
            Files.writeString(root.resolve("diary.md"),
                    "\n- " + Instant.now() + " " + line + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }

    public Map<String, Object> readJsonMap(Path path) {
        try {
            if (!Files.isRegularFile(path)) return Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
