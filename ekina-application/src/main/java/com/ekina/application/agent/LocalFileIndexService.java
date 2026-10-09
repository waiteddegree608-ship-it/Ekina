package com.ekina.application.agent;

import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lightweight content/path index for the PC-assistant agent (代理人).
 * Not a full search engine — enough for "哪份文件 / 大概什么内容".
 */
@Service
public class LocalFileIndexService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Value("${ekina.agent.allowed-roots:}")
    private String allowedRootsCsv;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    public Path indexFile() {
        return homeQuotaService.homePath().resolve("agents").resolve("agent").resolve("file-index.json");
    }

    public Path allowedRootsFile() {
        return homeQuotaService.homePath().resolve("agents").resolve("agent").resolve("allowed-roots.json");
    }

    public Path inboxDir() {
        return Path.of(sandboxDir, "uploads", "agent-inbox");
    }

    public List<Path> builtinRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(homeQuotaService.homePath().resolve("workspace").resolve("agent").normalize());
        roots.add(inboxDir().toAbsolutePath().normalize());
        return roots;
    }

    /** User-configured roots (UI + optional yaml bootstrap), hot-reloaded from disk. */
    public synchronized List<Path> customRoots() {
        LinkedHashMap<String, Path> uniq = new LinkedHashMap<>();
        // yaml / env bootstrap (still honored)
        if (allowedRootsCsv != null && !allowedRootsCsv.isBlank()) {
            for (String part : allowedRootsCsv.split("[;,]")) {
                addCustom(uniq, part);
            }
        }
        for (String part : loadCustomRootsFromDisk()) {
            addCustom(uniq, part);
        }
        return new ArrayList<>(uniq.values());
    }

    public List<Path> allowedRoots() {
        LinkedHashMap<String, Path> uniq = new LinkedHashMap<>();
        for (Path r : builtinRoots()) {
            uniq.put(r.toString().toLowerCase(Locale.ROOT), r);
        }
        for (Path r : customRoots()) {
            uniq.put(r.toString().toLowerCase(Locale.ROOT), r);
        }
        return new ArrayList<>(uniq.values());
    }

    public boolean isUnderAllowed(Path path) {
        if (path == null) return false;
        Path abs = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots()) {
            if (abs.startsWith(root)) return true;
        }
        return false;
    }

    public Map<String, Object> listRoots() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> builtin = new ArrayList<>();
        for (Path r : builtinRoots()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.toString());
            m.put("exists", Files.isDirectory(r));
            m.put("builtin", true);
            builtin.add(m);
        }
        List<Map<String, Object>> custom = new ArrayList<>();
        for (Path r : customRoots()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.toString());
            m.put("exists", Files.isDirectory(r));
            m.put("builtin", false);
            custom.add(m);
        }
        out.put("builtin", builtin);
        out.put("custom", custom);
        out.put("roots", allowedRoots().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p.toString());
            m.put("exists", Files.isDirectory(p));
            return m;
        }).collect(Collectors.toList()));
        out.put("hint", "在能力板「本机」粘贴绝对路径即可，立即生效，无需改 .env / 重启。");
        return out;
    }

    /**
     * Replace user custom roots with pasted paths (newline / semicolon / comma separated).
     * Builtin roots always remain. Invalid paths are rejected but valid ones still save.
     */
    public synchronized Map<String, Object> setCustomRootsFromText(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String line : raw.split("[\\r\\n;,]")) {
                String p = line.trim().replace("\"", "");
                if (!p.isBlank()) parts.add(p);
            }
        }
        return setCustomRoots(parts);
    }

    public synchronized Map<String, Object> setCustomRoots(List<String> paths) {
        List<String> saved = new ArrayList<>();
        List<Map<String, String>> rejected = new ArrayList<>();
        LinkedHashMap<String, Path> uniq = new LinkedHashMap<>();
        if (paths != null) {
            for (String part : paths) {
                if (part == null || part.isBlank()) continue;
                try {
                    Path abs = Path.of(part.trim().replace("\"", "")).toAbsolutePath().normalize();
                    if (!Files.isDirectory(abs)) {
                        Map<String, String> rej = new LinkedHashMap<>();
                        rej.put("path", abs.toString());
                        rej.put("reason", Files.exists(abs) ? "不是文件夹" : "路径不存在");
                        rejected.add(rej);
                        continue;
                    }
                    // Don't store builtins again
                    boolean isBuiltin = false;
                    for (Path b : builtinRoots()) {
                        if (b.equals(abs)) {
                            isBuiltin = true;
                            break;
                        }
                    }
                    if (isBuiltin) continue;
                    uniq.put(abs.toString().toLowerCase(Locale.ROOT), abs);
                } catch (Exception e) {
                    Map<String, String> rej = new LinkedHashMap<>();
                    rej.put("path", part);
                    rej.put("reason", e.getMessage() == null ? "无效路径" : e.getMessage());
                    rejected.add(rej);
                }
            }
        }
        for (Path p : uniq.values()) {
            saved.add(p.toString());
        }
        persistCustomRoots(saved);
        Map<String, Object> out = listRoots();
        out.put("ok", true);
        out.put("saved", saved);
        out.put("rejected", rejected);
        return out;
    }

    private void addCustom(LinkedHashMap<String, Path> uniq, String part) {
        if (part == null || part.isBlank()) return;
        try {
            Path abs = Path.of(part.trim().replace("\"", "")).toAbsolutePath().normalize();
            if (Files.isDirectory(abs)) {
                uniq.put(abs.toString().toLowerCase(Locale.ROOT), abs);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> loadCustomRootsFromDisk() {
        Path f = allowedRootsFile();
        try {
            if (!Files.isRegularFile(f)) return List.of();
            Map<String, Object> root = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            Object arr = root.get("roots");
            if (arr instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o != null) out.add(String.valueOf(o));
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private void persistCustomRoots(List<String> roots) {
        try {
            Path f = allowedRootsFile();
            Files.createDirectories(f.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("updatedAt", Instant.now().toString());
            root.put("roots", roots);
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[LocalFileIndex] save allowed-roots failed: " + e.getMessage());
        }
    }

    /** Index a folder (shallow-recursive, capped). */
    public Map<String, Object> indexFolder(String folderPath, int maxFiles) {
        Map<String, Object> out = new LinkedHashMap<>();
        int cap = Math.max(10, Math.min(2000, maxFiles <= 0 ? 400 : maxFiles));
        Path root = folderPath == null || folderPath.isBlank()
                ? homeQuotaService.homePath().resolve("workspace").resolve("agent")
                : Path.of(folderPath);
        Path abs = root.toAbsolutePath().normalize();
        if (!isUnderAllowed(abs) && !Files.isDirectory(abs)) {
            // Allow indexing newly granted absolute folder if it exists and matches configured roots only
        }
        if (!isUnderAllowed(abs)) {
            out.put("ok", false);
            out.put("error", "路径不在代理人允许根目录内: " + abs + "。先配置 allowed-roots 或把文件拖进对话框。");
            return out;
        }
        if (!Files.isDirectory(abs)) {
            out.put("ok", false);
            out.put("error", "不是目录: " + abs);
            return out;
        }

        List<Map<String, Object>> entries = loadEntries();
        // Drop previous entries under this root
        String rootStr = abs.toString();
        entries = entries.stream()
                .filter(e -> {
                    Object p = e.get("path");
                    return p == null || !String.valueOf(p).startsWith(rootStr);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        int counted = 0;
        try (Stream<Path> walk = Files.walk(abs, 6)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (counted >= cap) break;
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(".")) continue;
                if (name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so")) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("path", p.toAbsolutePath().normalize().toString());
                e.put("name", p.getFileName().toString());
                e.put("ext", extOf(name));
                e.put("size", Files.size(p));
                e.put("mtime", Files.getLastModifiedTime(p).toInstant().toString());
                e.put("snippet", snippet(p));
                e.put("root", rootStr);
                entries.add(e);
                counted++;
            }
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage());
            return out;
        }

        saveEntries(entries);
        out.put("ok", true);
        out.put("indexed", counted);
        out.put("root", rootStr);
        out.put("totalInIndex", entries.size());
        out.put("updatedAt", Instant.now().toString());
        return out;
    }

    public Map<String, Object> search(String query, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            out.put("ok", false);
            out.put("error", "query required");
            return out;
        }
        int n = Math.max(1, Math.min(50, limit <= 0 ? 12 : limit));
        String[] tokens = q.split("\\s+");
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> e : loadEntries()) {
            String hay = (str(e.get("name")) + " " + str(e.get("path")) + " " + str(e.get("snippet"))
                    + " " + str(e.get("mtime"))).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String t : tokens) {
                if (!hay.contains(t)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                hits.add(e);
                if (hits.size() >= n) break;
            }
        }
        out.put("ok", true);
        out.put("query", query);
        out.put("hits", hits);
        out.put("count", hits.size());
        if (hits.isEmpty()) {
            out.put("hint", "无命中。可先 indexLocalFolder，或放宽关键词（时间/扩展名/文件名片段）。");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadEntries() {
        Path f = indexFile();
        try {
            if (!Files.isRegularFile(f)) return new ArrayList<>();
            Map<String, Object> root = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class);
            Object arr = root.get("entries");
            if (arr instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add(new LinkedHashMap<>((Map<String, Object>) m));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    private void saveEntries(List<Map<String, Object>> entries) {
        try {
            Path f = indexFile();
            Files.createDirectories(f.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("updatedAt", Instant.now().toString());
            root.put("entries", entries);
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[LocalFileIndex] save failed: " + e.getMessage());
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1) : "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String snippet(Path p) {
        try {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".csv")
                    || name.endsWith(".json") || name.endsWith(".log") || name.endsWith(".py")
                    || name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx"))) {
                return "";
            }
            if (Files.size(p) > 200_000) return "(large text)";
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            String one = raw.replaceAll("\\s+", " ").trim();
            return one.length() > 180 ? one.substring(0, 180) : one;
        } catch (Exception e) {
            return "";
        }
    }
}
