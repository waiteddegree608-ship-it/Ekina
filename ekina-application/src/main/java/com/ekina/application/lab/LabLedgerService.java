package com.ekina.application.lab;

import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.project.ProjectBoardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ekina's academic / project accumulation folder.
 * Local source of truth: {@code AgentHome/lab/}. Sync to GitHub / Overleaf is {@link GitRemoteService}.
 */
@Service
public class LabLedgerService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ProjectBoardService projectBoardService;

    @PostConstruct
    public void init() {
        seed();
    }

    public Path root() {
        return homeQuotaService.homePath().resolve("lab");
    }

    public synchronized Map<String, Object> record(String shelf, String slug, String title, String body, String projectId) {
        String kind = normalizeShelf(shelf);
        String id = slugify(slug, title);
        Path dir = root().resolve(kind).resolve(id);
        try {
            Files.createDirectories(dir);
            Path log = dir.resolve("log.md");
            String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            String block = "\n## " + day + " · " + safe(title) + "\n\n" + safe(body) + "\n";
            if (!Files.exists(log)) {
                Files.writeString(log, "# " + (title == null || title.isBlank() ? id : title) + "\n"
                        + "shelf: " + kind + "\n"
                        + (projectId == null || projectId.isBlank() ? "" : "project: " + projectId + "\n")
                        + block, StandardCharsets.UTF_8);
            } else {
                Files.writeString(log, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", Instant.now().toString());
            row.put("shelf", kind);
            row.put("slug", id);
            row.put("title", title);
            row.put("projectId", projectId);
            row.put("path", log.toString());
            Files.writeString(root().resolve("ledger.jsonl"),
                    mapper.writeValueAsString(row) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rewriteIndex(kind);
            if (projectId != null && !projectId.isBlank()) {
                try {
                    projectBoardService.appendEvent(projectId, title, "已记入实验室账本 " + kind + "/" + id, "insight");
                } catch (Exception ignored) {
                }
            }
            return row;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String shelf : List.of("academic", "projects")) {
            Path dir = root().resolve(shelf);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).sorted().forEach(d -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("shelf", shelf);
                    row.put("slug", d.getFileName().toString());
                    Path log = d.resolve("log.md");
                    row.put("log", Files.isRegularFile(log) ? log.toString() : "");
                    out.add(row);
                });
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void seed() {
        try {
            Files.createDirectories(root().resolve("academic"));
            Files.createDirectories(root().resolve("projects"));
            Path readme = root().resolve("README.md");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        # 伊姬娜实验室

                        这里是总管的账本，不是施工现场。
                        - `academic/` 论文、实验、claim 与负结果
                        - `projects/` 已归档的非学术工作流（不再推进）
                        - `ledger.jsonl` 时间序追加记录

                        推到 GitHub 用 `syncLabGithub`；开新稿用 `openNewPaper`（不需要现成论文）。
                        Overleaf 不能用 token 建项目：主人给一个空白写稿桌 Git 地址后，`syncOverleaf` 自动 push。
                        伊姬娜只派活；CODER 动手；记完再入库。
                        """, StandardCharsets.UTF_8);
            }
            ensureLog("academic", "ddpm-sampling", "CIFAR-10 扩散采样",
                    "Run 001–004 已在算力平台跑完。卖点以 Run 004 的 ω×η 联合调优为准，不要再用 Run 001 被否定的 schedule-blend / guidance-annealing 正贡献。课题板：pf_ddpm_sampling。");
            ensureLog("academic", "tabicl", "TabICL / 表格 in-context learning",
                    "主表已合成。建议正文主打 few-shot + 多分类；scaling / Covertype 作副线。课题板：pf_tabicl。");
            ensureLog("academic", "hypelora", "HypeLoRA 复现",
                    "规划中。算力主机 glph6jf7:16031（A100-40GB）。课题板：pf_hypelora。");
            // AI 漫剧已停工：不再 ensureProgram，避免启动时重新挂上课题坞。
        } catch (Exception e) {
            System.err.println("[LabLedger] seed: " + e.getMessage());
        }
    }

    private void ensureLog(String shelf, String slug, String title, String body) throws Exception {
        Path log = root().resolve(shelf).resolve(slug).resolve("log.md");
        if (Files.exists(log)) return;
        record(shelf, slug, title, body, null);
    }

    private void rewriteIndex(String shelf) throws Exception {
        StringBuilder sb = new StringBuilder("# ").append(shelf).append("\n\n");
        Path dir = root().resolve(shelf);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).sorted().forEach(d ->
                        sb.append("- [").append(d.getFileName()).append("](")
                                .append(d.getFileName()).append("/log.md)\n"));
            }
        }
        Files.writeString(dir.resolve("INDEX.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String normalizeShelf(String shelf) {
        String s = shelf == null ? "" : shelf.trim().toLowerCase(Locale.ROOT);
        if (s.contains("proj") || s.contains("产品") || s.contains("漫剧") || s.contains("工作流")) return "projects";
        return "academic";
    }

    private static String slugify(String slug, String title) {
        String s = slug == null || slug.isBlank() ? title : slug;
        if (s == null) s = "note";
        s = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff\\-]+", "-");
        if (s.isBlank()) s = "note";
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
