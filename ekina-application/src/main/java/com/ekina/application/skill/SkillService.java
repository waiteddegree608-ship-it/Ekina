package com.ekina.application.skill;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads skill markdown playbooks from workspace/skills.
 */
@Service
public class SkillService {

    private final List<SkillDefinition> skills = new ArrayList<>();

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @Value("${ekina.skills.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized void reload() {
        skills.clear();
        if (!enabled) {
            return;
        }
        Path dir = Path.of(workspaceDir, "skills");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("SKILL.md") ||
                            (p.getParent().equals(dir) && p.getFileName().toString().endsWith(".md") && !p.getFileName().toString().equals("README.md")))
                    .sorted()
                    .forEach(this::loadOne);
        } catch (Exception e) {
            System.err.println("[SkillService] reload failed: " + e.getMessage());
        }
        System.out.println("[SkillService] loaded " + skills.size() + " skills from " + dir);
    }

    private void loadOne(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            SkillDefinition s = new SkillDefinition();
            String name = path.getFileName().toString().equals("SKILL.md")
                    ? path.getParent().getFileName().toString()
                    : path.getFileName().toString().replace(".md", "");
            if (name.startsWith("paper-")) name = name.replaceFirst("paper-", "paper.");
            s.setId(name);
            s.setPath(path.toString());
            s.setTitle(name);
            s.setBody(raw);
            // title from first heading
            Matcher hm = Pattern.compile("(?m)^#\\s+(.+)$").matcher(raw);
            if (hm.find()) {
                s.setTitle(hm.group(1).trim());
            }
            // tools: lines with `toolName` or watchVideo(
            Set<String> tools = new LinkedHashSet<>();
            Matcher tm = Pattern.compile("`([a-zA-Z][a-zA-Z0-9_]{1,40})`").matcher(raw);
            while (tm.find()) {
                String t = tm.group(1);
                if (looksLikeTool(t)) {
                    tools.add(t);
                }
            }
            Matcher tm2 = Pattern.compile("\\b(watchVideo|fetchBilibiliTranscript|searchWeb|exploreBilibili|updateStateCard|getStateCard|updateChannelCard|generateWordDocument|writeMarkdownNote|readDocument|annotatePdf|transcribeAudio|runLifeHeartbeat)\\b")
                    .matcher(raw);
            while (tm2.find()) {
                tools.add(tm2.group(1));
            }
            s.setTools(new ArrayList<>(tools));
            // triggers: Chinese/English keywords under 何时使用 section roughly whole body for matching
            List<String> triggers = new ArrayList<>();
            for (String kw : List.of("视频", "B站", "bilibili", "看视频", "总结视频", "论文", "提醒", "todo", "文档",
                    "热梗", "热门", "番剧", "新番", "刷", "探索", "派活", "总管", "漫剧", "github", "overleaf", "账本", "算力",
                    "骋风", "cfgpu", "开关机", "实例",
                    "期刊", "连载", "短摘", "deepseek", "模型", "分流", "额度", "余额",
                    "排版", "美化", "模板", "neurips", "icml", "ieee", "springer",
                    "封面", "监督", "验收", "图表", "审计")) {
                if (raw.toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
                    triggers.add(kw);
                }
            }
            s.setTriggers(triggers);
            skills.add(s);
        } catch (Exception e) {
            System.err.println("[SkillService] load " + path + " failed: " + e.getMessage());
        }
    }

    private boolean looksLikeTool(String t) {
        if (t == null || t.length() < 3) return false;
        // exclude common markdown words
        Set<String> ban = Set.of("Skill", "Markdown", "Word", "PDF", "JSON", "API", "HTTP", "TODO");
        if (ban.contains(t)) return false;
        return Character.isLowerCase(t.charAt(0)) || t.contains("Video") || t.contains("Document");
    }

    public List<SkillDefinition> list() {
        return new ArrayList<>(skills);
    }

    public List<SkillDefinition> match(String userMessage) {
        if (userMessage == null || userMessage.isBlank() || skills.isEmpty()) {
            return List.of();
        }
        String q = userMessage.toLowerCase(Locale.ROOT);
        List<SkillDefinition> hit = new ArrayList<>();
        for (SkillDefinition s : skills) {
            boolean matched = false;
            for (String tr : s.getTriggers()) {
                if (q.contains(tr.toLowerCase(Locale.ROOT))) {
                    matched = true;
                    break;
                }
            }
            if (!matched && s.getId() != null && q.contains(s.getId().toLowerCase(Locale.ROOT))) {
                matched = true;
            }
            if (matched) {
                hit.add(s);
            }
        }
        return hit;
    }

    /**
     * Compact skill injection for system prompt (matched skills first, else catalog).
     */
    public String buildPromptSectionByPrefix(String userMessage, String idPrefix) {
        if (!enabled || skills.isEmpty()) return "";
        List<SkillDefinition> pool = new ArrayList<>();
        for (SkillDefinition s : skills) {
            if (s.getId() != null && s.getId().startsWith(idPrefix)) pool.add(s);
        }
        if (pool.isEmpty()) return buildPromptSection(userMessage);
        StringBuilder sb = new StringBuilder();
        sb.append("\n[论文排版 Skills]\n");
        List<SkillDefinition> matched = match(userMessage).stream()
                .filter(s -> s.getId() != null && s.getId().startsWith(idPrefix))
                .collect(Collectors.toList());
        List<SkillDefinition> use = matched.isEmpty() ? pool : matched;
        for (SkillDefinition s : use) {
            sb.append("---\n").append(s.getTitle()).append(" (").append(s.getId()).append(")\n");
            String body = s.getBody() == null ? "" : s.getBody();
            if (body.length() > 2200) body = body.substring(0, 2200) + "\n...[skill truncated]...\n";
            sb.append(body).append("\n");
        }
        return sb.toString();
    }

    public String buildPromptSection(String userMessage) {
        if (!enabled || skills.isEmpty()) {
            return "";
        }
        List<SkillDefinition> matched = match(userMessage);
        StringBuilder sb = new StringBuilder();
        sb.append("\n[Skills 技能剧本]\n");
        if (!matched.isEmpty()) {
            sb.append("与当前请求相关的技能（请优先遵守）：\n");
            for (SkillDefinition s : matched) {
                sb.append("---\n").append(s.getTitle()).append(" (").append(s.getId()).append(")\n");
                // cap body to keep prompt size sane
                String body = s.getBody() == null ? "" : s.getBody();
                if (body.length() > 2500) {
                    body = body.substring(0, 2500) + "\n...[skill truncated]...\n";
                }
                sb.append(body).append("\n");
            }
        } else {
            sb.append("可用技能目录（需要时再严格按剧本执行）：\n");
            for (SkillDefinition s : skills) {
                sb.append("- ").append(s.getId()).append(": ").append(s.getTitle());
                if (!s.getTools().isEmpty()) {
                    sb.append(" [tools: ").append(String.join(", ", s.getTools())).append("]");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public Set<String> preferredTools(String userMessage) {
        return match(userMessage).stream()
                .flatMap(s -> s.getTools().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
