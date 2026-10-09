package com.ekina.application.project;

import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Project folders for companion UI.
 * <p>User dock ≈ Cursor repos: {@code portfolio} / high-level {@code mission} / {@code paper}.
 * Fine-grained {@code task} rows are Ekina's internal work ledger (planning / coding-agent collab),
 * not shown as separate dock cards.
 */
@Service
public class ProjectService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ProjectRecord> projects = new ArrayList<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    @PostConstruct
    public void init() {
        load();
        ensureDaily(defaultUser);
        migrateLegacyChannel(defaultUser);
        // Attach historical agent tasks under portfolios — do NOT explode the user dock
        backfillFromAgentTasks();
        consolidateTaskProjectsIntoPortfolios(defaultUser);
        curateOwnerDock(defaultUser);
    }

    /** Link existing agent_tasks into portfolios (internal ledger), not one card per task. */
    private void backfillFromAgentTasks() {
        try {
            Path f = homeQuotaService.homePath().resolve("state").resolve("agent_tasks.json");
            if (!Files.isRegularFile(f)) return;
            List<Map<String, Object>> tasks = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            if (tasks == null) return;
            int n = 0;
            for (Map<String, Object> t : tasks) {
                if (n >= 80) break;
                String id = str(t.get("id"));
                String title = str(t.get("title"));
                String uid = str(t.get("userId"));
                if (uid.isBlank()) uid = defaultUser;
                Object assignee = t.get("assignee");
                String role = assignee == null ? "CODER" : String.valueOf(assignee);
                if (id.isBlank()) continue;
                Object mid = t.get("meta") instanceof Map<?, ?> mm ? mm.get("missionId") : null;
                String parent = mid == null ? null : findMissionParentId(uid, String.valueOf(mid));
                ensureForTask(uid, id, title.isBlank() ? id : title, role, parent);
                n++;
            }
            if (n > 0) System.out.println("[ProjectService] backfilled " + n + " tasks under portfolios");
        } catch (Exception e) {
            System.err.println("[ProjectService] backfill skipped: " + e.getMessage());
        }
    }

    private String findMissionParentId(String uid, String missionId) {
        if (missionId == null || missionId.isBlank()) return null;
        for (ProjectRecord p : projects) {
            if (!uid.equals(p.getUserId())) continue;
            if ("mission".equals(p.getSourceType()) && missionId.equals(p.getSourceId())) {
                Object parent = p.getMeta() == null ? null : p.getMeta().get("parentId");
                return parent != null ? String.valueOf(parent) : p.getId();
            }
            if ("portfolio".equals(p.getSourceType()) && p.getMeta() != null) {
                Object linked = p.getMeta().get("linkedMissionIds");
                if (linked instanceof List<?> list && list.stream().anyMatch(x -> missionId.equals(String.valueOf(x)))) {
                    return p.getId();
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Path root() {
        return homeQuotaService.homePath().resolve("workspace").resolve("projects");
    }

    private Path indexFile() {
        return root().resolve("index.json");
    }

    public synchronized List<ProjectRecord> list(String userId) {
        String uid = blankToDefault(userId);
        ensureDaily(uid);
        return projects.stream()
                .filter(p -> uid.equals(p.getUserId()) || p.getUserId() == null)
                .sorted(Comparator.comparing(ProjectRecord::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .collect(Collectors.toList());
    }

    public synchronized Optional<ProjectRecord> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return projects.stream().filter(p -> id.equals(p.getId())).findFirst();
    }

    public synchronized ProjectRecord ensureDaily(String userId) {
        String uid = blankToDefault(userId);
        Optional<ProjectRecord> existing = projects.stream()
                .filter(p -> uid.equals(p.getUserId()) && ChatSessionContext.DAILY.equals(p.getId()))
                .findFirst();
        if (existing.isPresent()) return existing.get();
        ProjectRecord p = new ProjectRecord();
        p.setId(ChatSessionContext.DAILY);
        p.setTitle("日常");
        p.setSourceType("daily");
        p.setSourceId(ChatSessionContext.DAILY);
        p.setThreadId(ChatSessionContext.DAILY);
        p.setUserId(uid);
        String now = Instant.now().toString();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        projects.add(0, p);
        save();
        return p;
    }

    /**
     * Attach a coding-agent task under a user-facing portfolio (never a dock card of its own).
     *
     * @param parentProjectId preferred parent (mission/portfolio id); null → infer portfolio from title
     */
    public synchronized ProjectRecord ensureForTask(String userId, String taskId, String title, String assignee) {
        return ensureForTask(userId, taskId, title, assignee, null);
    }

    public synchronized ProjectRecord ensureForTask(String userId, String taskId, String title, String assignee,
                                                    String parentProjectId) {
        String uid = blankToDefault(userId);
        String cleanTitle = (title == null || title.isBlank()) ? taskId : title.trim();

        ProjectRecord parent = null;
        if (parentProjectId != null && !parentProjectId.isBlank()) {
            parent = get(parentProjectId).orElse(null);
        }
        if (parent == null) {
            parent = ensurePortfolio(uid, inferPortfolioTitle(cleanTitle));
        }

        if (taskId != null && !parent.getLinkedTaskIds().contains(taskId)) {
            parent.getLinkedTaskIds().add(taskId);
        }
        parent.getMeta().put("lastAssignee", assignee == null ? "" : assignee);
        parent.getMeta().put("lastTaskTitle", trim(cleanTitle, 80));
        touch(parent);

        // Keep a hidden task ledger row for Ekina (audit / planning), marked internal
        Optional<ProjectRecord> taskRow = projects.stream()
                .filter(p -> uid.equals(p.getUserId()) && "task".equals(p.getSourceType()) && taskId.equals(p.getSourceId()))
                .findFirst();
        if (taskRow.isEmpty() && taskId != null) {
            ProjectRecord internal = create(uid, cleanTitle, "task", taskId, assignee);
            internal.getMeta().put("internal", true);
            internal.getMeta().put("parentId", parent.getId());
            internal.setThreadId(parent.getThreadId());
        } else if (taskRow.isPresent()) {
            ProjectRecord internal = taskRow.get();
            internal.getMeta().put("internal", true);
            internal.getMeta().put("parentId", parent.getId());
            internal.setThreadId(parent.getThreadId());
            touch(internal);
        }
        save();
        return parent;
    }

    /**
     * User-facing project for a Mission goal. Returns the <b>portfolio</b> card the owner sees;
     * the mission row is kept as an internal child for Ekina's DAG.
     */
    public synchronized ProjectRecord ensureForMission(String userId, String missionId, String goal) {
        String uid = blankToDefault(userId);
        String portfolioTitle = inferPortfolioTitle(goal == null || goal.isBlank() ? missionId : goal);
        ProjectRecord portfolio = ensurePortfolio(uid, portfolioTitle);

        @SuppressWarnings("unchecked")
        List<String> linked = (List<String>) portfolio.getMeta()
                .computeIfAbsent("linkedMissionIds", k -> new ArrayList<String>());
        if (missionId != null && !linked.contains(missionId)) {
            linked.add(missionId);
        }
        portfolio.getMeta().put("lastGoal", trim(goal, 120));
        touch(portfolio);

        Optional<ProjectRecord> existing = projects.stream()
                .filter(p -> uid.equals(p.getUserId()) && "mission".equals(p.getSourceType()) && missionId.equals(p.getSourceId()))
                .findFirst();
        if (existing.isPresent()) {
            ProjectRecord m = existing.get();
            m.getMeta().put("internal", true);
            m.getMeta().put("parentId", portfolio.getId());
            m.setThreadId(portfolio.getThreadId());
            touch(m);
        } else {
            String title = goal == null || goal.isBlank() ? missionId : trim(goal, 48);
            ProjectRecord m = create(uid, title, "mission", missionId, null);
            m.getMeta().put("internal", true);
            m.getMeta().put("parentId", portfolio.getId());
            m.setThreadId(portfolio.getThreadId());
        }
        save();
        return portfolio;
    }

    /** Owner-visible "repo" card — Cursor-sidebar equivalent. */
    /** Explicit research requests must not be merged by the legacy fuzzy title matcher. */
    public synchronized ProjectRecord createResearch(String userId, String title, String entry, String requestId) {
        String clean = ResearchPlan.required(title, 2000);
        if (!List.of("idea", "explore", "review").contains(entry)) throw new IllegalArgumentException("无效研究入口");
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{8,80}")) throw new IllegalArgumentException("无效请求编号");
        String id = "research_" + requestId;
        Optional<ProjectRecord> existing = get(id);
        if (existing.isPresent()) {
            ProjectRecord p = existing.get();
            if (!blankToDefault(userId).equals(p.getUserId()) || !clean.equals(p.getMeta().get("brief"))
                    || !entry.equals(p.getMeta().get("researchEntry"))) throw new IllegalArgumentException("请求编号冲突");
            return p;
        }
        ProjectRecord p = new ProjectRecord();
        p.setId(id);
        p.setSourceId(id);
        p.setSourceType("portfolio");
        p.setTitle(trim(clean, 64));
        p.setThreadId(id);
        p.setUserId(blankToDefault(userId));
        p.setContactId("ekina");
        p.setCreatedAt(Instant.now().toString());
        p.setUpdatedAt(p.getCreatedAt());
        p.getMeta().put("userFacing", true);
        p.getMeta().put("researchEntry", entry);
        p.getMeta().put("program", true);
        p.getMeta().put("brief", clean);
        projects.add(0, p);
        try { save(); } catch (RuntimeException e) { projects.remove(p); throw e; }
        return p;
    }

    public synchronized ProjectRecord ensurePortfolio(String userId, String title) {
        String uid = blankToDefault(userId);
        String clean = (title == null || title.isBlank()) ? "未命名项目" : trim(title, 40);
        Optional<ProjectRecord> existing = projects.stream()
                .filter(p -> uid.equals(p.getUserId())
                        && "portfolio".equals(p.getSourceType())
                        && clean.equalsIgnoreCase(p.getTitle()))
                .findFirst();
        if (existing.isPresent()) {
            touch(existing.get());
            save();
            return existing.get();
        }
        // Soft match: title contains / contained
        for (ProjectRecord p : projects) {
            if (!uid.equals(p.getUserId()) || !"portfolio".equals(p.getSourceType())) continue;
            String t = p.getTitle() == null ? "" : p.getTitle();
            if (!t.isBlank() && (t.contains(clean) || clean.contains(t))) {
                touch(p);
                save();
                return p;
            }
        }
        if (isFragmentProgressTitle(clean) || isEphemeralTitle(clean) || isNoiseTitle(clean)) {
            return ensureProgramForHint(uid, clean);
        }
        ProjectRecord p = create(uid, clean, "portfolio", "pf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), null);
        if (isEphemeralTitle(clean)) {
            p.getMeta().put("ephemeral", true);
            p.getMeta().put("userFacing", false);
            p.getMeta().put("internal", true);
        } else {
            p.getMeta().put("userFacing", true);
        }
        save();
        return p;
    }

    public synchronized ProjectRecord ensureProgramForHint(String userId, String hint) {
        String uid = blankToDefault(userId);
        String[] prog = classifyProgram(hint);
        return ensureProgram(uid, prog[0], prog[1]);
    }

    /** Lasting owner-facing programs. One card, one board, not one card per chat sentence. */
    public synchronized ProjectRecord ensureProgram(String userId, String id, String title) {
        String uid = blankToDefault(userId);
        Optional<ProjectRecord> existing = get(id);
        if (existing.isPresent()) {
            ProjectRecord p = existing.get();
            p.getMeta().put("program", true);
            p.getMeta().put("internal", false);
            p.getMeta().put("userFacing", true);
            p.getMeta().remove("ephemeral");
            if (title != null && !title.isBlank()) p.setTitle(title);
            touch(p);
            save();
            return p;
        }
        ProjectRecord p = create(uid, title, "portfolio", id, null);
        p.setId(id);
        p.setThreadId(id);
        p.getMeta().put("program", true);
        p.getMeta().put("userFacing", true);
        p.getMeta().put("internal", false);
        save();
        return p;
    }

    /** Take a non-research program off the owner dock and stop treating it as live work. */
    private synchronized void hideProgram(String userId, String id) {
        if (id == null || id.isBlank()) return;
        get(id).ifPresent(p -> {
            p.getMeta().put("program", false);
            p.getMeta().put("userFacing", false);
            p.getMeta().put("internal", true);
            p.getMeta().put("paused", true);
            p.getMeta().put("pauseReason", "non-research");
            touch(p);
        });
    }

    private static String[] classifyProgram(String hint) {
        String h = hint == null ? "" : hint;
        String lower = h.toLowerCase(Locale.ROOT);
        if (lower.contains("tabicl") || lower.contains("tabpfn") || lower.contains("tabular")
                || lower.contains("covertype") || lower.contains("tabarena") || lower.contains("tabfm")
                || h.contains("表格") || h.contains("Few-shot") || h.contains("german_credit")
                || h.contains("Vehicle")) {
            return new String[] {"pf_tabicl", "TabICL / 表格 in-context learning"};
        }
        if (lower.contains("hypelora") || lower.contains("lora") || h.contains("算力平台复现")) {
            return new String[] {"pf_hypelora", "HypeLoRA 复现"};
        }
        return new String[] {"pf_ddpm_sampling", "CIFAR-10 扩散采样 · ω×η 联合调优"};
    }

    private synchronized void curateOwnerDock(String userId) {
        String uid = blankToDefault(userId);
        ensureProgram(uid, "pf_ddpm_sampling", "CIFAR-10 扩散采样 · ω×η 联合调优");
        ensureProgram(uid, "pf_tabicl", "TabICL / 表格 in-context learning");
        ensureProgram(uid, "pf_hypelora", "HypeLoRA 复现");
        hideProgram(uid, "pf_ai_drama");
        int hidden = 0;
        for (ProjectRecord p : projects) {
            if (!uid.equals(p.getUserId())) continue;
            if (Boolean.TRUE.equals(p.getMeta().get("program"))) continue;
            String st = p.getSourceType() == null ? "" : p.getSourceType();
            if ("portfolio".equals(st) || "paper".equals(st) || "mission".equals(st)) {
                p.getMeta().put("internal", true);
                p.getMeta().put("userFacing", false);
                hidden++;
            }
        }
        save();
        System.out.println("[ProjectService] curated owner dock; hid " + hidden + " fragment cards");
    }

    /**
     * Collapse legacy per-task / mis-created micro portfolios into real portfolios.
     * Keeps detail rows for Ekina; dock only shows user-facing portfolios.
     */
    public synchronized void consolidateTaskProjectsIntoPortfolios(String userId) {
        String uid = blankToDefault(userId);
        int moved = 0;
        for (ProjectRecord p : new ArrayList<>(projects)) {
            if (!uid.equals(p.getUserId())) continue;
            if (p.getMeta().containsKey("researchEntry")) continue;
            String st = p.getSourceType() == null ? "" : p.getSourceType();
            String title = p.getTitle() == null ? "" : p.getTitle();
            String titleLower = title.toLowerCase(Locale.ROOT);
            boolean canonical = "HypeLoRA 复现".equals(title) || "算力平台复现".equals(title)
                    || "TabPFN 复现".equals(title);
            boolean ephemeral = isEphemeralTitle(title) || isTickJunkTitle(title);
            boolean microPortfolio = "portfolio".equals(st) && !canonical
                    && (looksLikeTrainingOps(title, titleLower) || isNoiseTitle(title) || ephemeral
                    || isTickJunkTitle(title) || titleLower.contains("tabpfn") || title.contains("TabPFN"));
            // Old mission rows become Ekina-internal children of a portfolio
            boolean microMission = "mission".equals(st) && !canonical;
            boolean taskRow = "task".equals(st);
            if (!taskRow && !microPortfolio && !microMission) continue;
            if (Boolean.TRUE.equals(p.getMeta().get("internal")) && p.getMeta().get("parentId") != null
                    && taskRow) {
                continue;
            }
            if (ephemeral || isTickJunkTitle(title)) {
                p.getMeta().put("ephemeral", true);
                p.getMeta().put("internal", true);
                p.getMeta().put("userFacing", false);
                moved++;
                continue;
            }
            ProjectRecord portfolio = ensurePortfolio(uid, inferPortfolioTitle(p.getTitle()));
            if (portfolio.getId().equals(p.getId())) continue;
            if (p.getSourceId() != null && !portfolio.getLinkedTaskIds().contains(p.getSourceId())) {
                portfolio.getLinkedTaskIds().add(p.getSourceId());
            }
            for (String tid : new ArrayList<>(p.getLinkedTaskIds())) {
                if (!portfolio.getLinkedTaskIds().contains(tid)) {
                    portfolio.getLinkedTaskIds().add(tid);
                }
            }
            p.getMeta().put("internal", true);
            p.getMeta().put("parentId", portfolio.getId());
            p.setThreadId(portfolio.getThreadId());
            touch(portfolio);
            moved++;
        }
        moved += rehomeMisfiledResearchPortfolios(uid);
        if (moved > 0) {
            save();
            System.out.println("[ProjectService] consolidated " + moved + " micro rows under portfolios");
        }
    }

    /** TabPFN tasks/portfolios wrongly parked under HypeLoRA because of「环境」keyword. */
    private int rehomeMisfiledResearchPortfolios(String uid) {
        int moved = 0;
        ProjectRecord tabpfn = null;
        for (ProjectRecord p : new ArrayList<>(projects)) {
            if (!uid.equals(p.getUserId())) continue;
            if (p.getMeta().containsKey("researchEntry")) continue;
            String title = p.getTitle() == null ? "" : p.getTitle();
            String lower = title.toLowerCase(Locale.ROOT);
            boolean isTab = lower.contains("tabpfn") || title.contains("TabPFN")
                    || title.contains("表格基础模型");
            if (!isTab) continue;
            if ("TabPFN 复现".equals(title) && "portfolio".equals(p.getSourceType())) {
                tabpfn = p;
                continue;
            }
            if (tabpfn == null) {
                tabpfn = ensurePortfolio(uid, "TabPFN 复现");
            }
            if (tabpfn.getId().equals(p.getId())) continue;
            if (p.getSourceId() != null && !tabpfn.getLinkedTaskIds().contains(p.getSourceId())) {
                tabpfn.getLinkedTaskIds().add(p.getSourceId());
            }
            for (String tid : new ArrayList<>(p.getLinkedTaskIds())) {
                if (!tabpfn.getLinkedTaskIds().contains(tid)) {
                    tabpfn.getLinkedTaskIds().add(tid);
                }
            }
            // Detach from HypeLoRA linked list if present
            for (ProjectRecord other : projects) {
                if (!uid.equals(other.getUserId())) continue;
                if (!"HypeLoRA 复现".equals(other.getTitle())) continue;
                other.getLinkedTaskIds().removeIf(id ->
                        id != null && (id.equals(p.getSourceId()) || p.getLinkedTaskIds().contains(id)));
                Object last = other.getMeta().get("lastTaskTitle");
                if (last != null && String.valueOf(last).toLowerCase(Locale.ROOT).contains("tabpfn")) {
                    other.getMeta().remove("lastTaskTitle");
                }
            }
            if ("portfolio".equals(p.getSourceType()) && !"TabPFN 复现".equals(title)) {
                p.getMeta().put("internal", true);
                p.getMeta().put("parentId", tabpfn.getId());
                p.getMeta().put("userFacing", false);
            } else {
                p.getMeta().put("internal", true);
                p.getMeta().put("parentId", tabpfn.getId());
            }
            p.setThreadId(tabpfn.getThreadId());
            touch(tabpfn);
            moved++;
        }
        return moved;
    }

    /**
     * Map a noisy agent/task title onto a stable portfolio name the owner recognizes.
     * Micro ops (SSH / GPU / epoch) collapse into the research portfolio — not their own cards.
     */
    public String inferPortfolioTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        t = t.replaceFirst("(?i)^\\[msn_[a-z0-9_\\-]+/[^\\]]+\\]\\s*", "");
        t = t.replaceFirst("(?i)^\\[msn_[a-z0-9_\\-]+\\]\\s*", "");
        t = t.replaceFirst("(?i)^#+\\s*", "");
        t = t.replaceFirst("(?i)^tick\\s*#?\\d+\\s*[·•\\-:]?\\s*", "");
        String lower = t.toLowerCase(Locale.ROOT);
        // Autonomy slogans must never become their own dock "repos"
        if (isTickJunkTitle(raw) || isTickJunkTitle(t)) {
            if (looksLikeTrainingOps(t, lower) || lower.contains("lora") || t.contains("复现")) {
                return "HypeLoRA 复现";
            }
            return "";
        }
        // TabPFN is its own research line — never fold into HypeLoRA because of「环境」
        if (lower.contains("tabpfn") || t.contains("TabPFN") || t.contains("表格基础模型")
                || t.contains("tabular foundation")) {
            return "TabPFN 复现";
        }
        if (lower.contains("hypelora") || lower.contains("hyper-lora") || lower.contains("hyperlora")
                || t.contains("HypeLoRA") || t.contains("Hype Lora") || lower.contains("tc-lora")) {
            return "HypeLoRA 复现";
        }
        if (t.contains("算力平台") || (t.contains("算力") && (t.contains("复现") || t.contains("平台")))) {
            return "算力平台复现";
        }
        // Ops that are clearly part of the ongoing training repro — never own a dock card
        if (looksLikeTrainingOps(t, lower)) {
            return "HypeLoRA 复现";
        }
        if (lower.contains("paper") || t.contains("论文")) {
            return trim(t, 32);
        }
        // Ignore smoke / hello bridge leftovers as their own tiny portfolios still OK
        return trim(t.isBlank() ? "进行中的工作" : t, 32);
    }

    private static boolean looksLikeTrainingOps(String t, String lower) {
        // TabPFN / other papers must not be mis-classified as HypeLoRA ops
        if (lower.contains("tabpfn") || t.contains("TabPFN")) return false;
        if (lower.contains("epoch") || lower.contains("wandb") || lower.contains("cola")
                || lower.contains("lora") || lower.contains("gpu") || lower.contains("a100")
                || lower.contains("paramiko") || lower.contains("ssh") || lower.contains("pca")
                || lower.contains("baseline") || lower.contains("rank") || lower.contains("phase")) {
            return true;
        }
        // 「环境/服务器」 alone is too broad — only when clearly LoRA/Hype training context
        if (t.contains("训练") || t.contains("验证集") || t.contains("checkpoint")
                || (t.contains("复现") && (lower.contains("lora") || lower.contains("hype")))) {
            return true;
        }
        if ((t.contains("环境") || t.contains("服务器") || t.contains("实验") || t.contains("对比"))
                && (lower.contains("lora") || lower.contains("hype") || lower.contains("dora")
                || lower.contains("cola"))) {
            return true;
        }
        return false;
    }

    private static boolean isNoiseTitle(String title) {
        if (title == null || title.isBlank()) return false;
        if (isTickJunkTitle(title)) return true;
        String t = title.toLowerCase(Locale.ROOT).trim();
        return t.startsWith("smoke") || t.startsWith("hello") || t.startsWith("go-smoke")
                || t.startsWith("claw-smoke") || t.contains("烟雾测试") || t.contains("任务卡交互");
    }

    /** Autonomy internal slogans like "## tick #504 · 我刚才做了什么" — never dock cards. */
    public static boolean isTickJunkTitle(String title) {
        if (title == null || title.isBlank()) return false;
        String t = title.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\btick\\s*#?\\d+.*")) return true;
        if (t.contains("我刚才做了什么") || t.contains("刚才做了什么") || t.contains("自主巡查") || t.contains("巡查报告")) return true;
        if (t.matches("(?i)^#+\\s*tick.*")) return true;
        if (t.replace("*", "").trim().startsWith("姬娜刚才")) return true;
        return "汇报".equals(t) || t.endsWith(" 汇报") || t.equals("结果汇报");
    }

    /**
     * One-shot owner asks (惊喜短笺 / 资讯 / 握手令牌…) — chat notifications, not dock "repos".
     */
    public static boolean isEphemeralTitle(String title) {
        if (title == null || title.isBlank()) return false;
        if (isTickJunkTitle(title)) return true;
        String t = title.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("tabpfn") || t.contains("TabPFN")) return false;
        if (looksLikeTrainingOps(t, lower)) return false;
        if (lower.contains("hypelora") || t.contains("算力")) return false;
        return t.contains("惊喜") || t.contains("短笺") || t.contains("资讯") || t.contains("日报")
                || t.contains("握手") || t.contains("令牌") || t.contains("磨合")
                || t.contains("贺卡") || t.contains("便条") || t.contains("小纸条")
                || (t.contains("报告") && !t.contains("实验") && !t.contains("训练"))
                || t.contains("汇报");
    }

    /** Projects the owner should see in the right dock (not internal task ledger). */
    public synchronized List<ProjectRecord> listDock(String userId) {
        return listDock(userId, "ekina");
    }

    /**
     * Dock filtered by owning contact. Legacy rows without contactId are treated as {@code ekina}.
     */
    public synchronized List<ProjectRecord> listDock(String userId, String contactId) {
        String uid = blankToDefault(userId);
        String cid = blankContact(contactId);
        // Portfolio consolidation is ekina-life only
        if ("ekina".equals(cid)) {
            consolidateTaskProjectsIntoPortfolios(uid);
        }
        return list(uid).stream()
                .filter(p -> cid.equals(blankContact(p.getContactId())))
                .filter(this::isUserFacing)
                .sorted(Comparator.comparing(ProjectRecord::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .collect(Collectors.toList());
    }

    public boolean isUserFacing(ProjectRecord p) {
        if (p == null) return false;
        String st = p.getSourceType() == null ? "" : p.getSourceType().toLowerCase(Locale.ROOT);
        if ("daily".equals(st) || "task".equals(st)) return false;
        if (Boolean.TRUE.equals(p.getMeta().get("internal"))) return false;
        if (Boolean.TRUE.equals(p.getMeta().get("ephemeral"))) return false;
        if ("portfolio".equals(st) && p.getMeta().containsKey("researchEntry")) return true;
        if (p.getMeta().get("parentId") != null && !"portfolio".equals(st)) return false;
        // Missions are Ekina-internal once folded; dock is portfolios / papers
        if ("mission".equals(st)) return false;
        // Paper pipeline clones and smoke runs stay off the owner dock.
        if ("paper".equals(st)) {
            return Boolean.TRUE.equals(p.getMeta().get("program"));
        }
        if (isNoiseTitle(p.getTitle())) return false;
        if (isEphemeralTitle(p.getTitle())) return false;
        if (isFragmentProgressTitle(p.getTitle())) return false;
        if ("portfolio".equals(st)) {
            return Boolean.TRUE.equals(p.getMeta().get("program"));
        }
        return false;
    }

    /**
     * One-shot chat slogans that used to mint a new dock card per sentence.
     * They fold into a lasting program (扩散 / TabICL / HypeLoRA), they are not projects.
     */
    public static boolean isFragmentProgressTitle(String title) {
        if (title == null || title.isBlank()) return false;
        String t = title.trim();
        if (t.startsWith("---") || t.startsWith("**做了什么")) return true;
        return t.contains("论文初稿")
                || t.contains("完成了论文")
                || t.contains("更新了状态")
                || t.startsWith("写了一")
                || t.startsWith("写了一个")
                || t.startsWith("搜集了")
                || t.startsWith("尝试在")
                || t.startsWith("成功读取")
                || t.startsWith("在服务器上")
                || t.contains("Experiments 部分")
                || t.contains("Introduction/Method")
                || t.contains("33K")
                || t.contains("TabArena")
                || t.contains("TabFM")
                || t.contains("german_credit")
                || t.contains("Thinking Mode")
                || t.contains("数据准备脚本")
                || t.contains("可执行的小实验")
                || t.contains("深入调研")
                || t.contains("烟雾测试")
                || t.contains("草拟对比");
    }

    /** Touch a lasting portfolio after a substantial outcome (progress freshness for the dock). */
    public synchronized void noteProgress(String userId, String hint) {
        String uid = blankToDefault(userId);
        String h = hint == null ? "" : hint.trim();
        if (isTickJunkTitle(h) || isNoiseTitle(h)) {
            // Fold training-related autonomy noise into HypeLoRA; never mint a tick card
            if (!looksLikeTrainingOps(h, h.toLowerCase(Locale.ROOT))
                    && !h.toLowerCase(Locale.ROOT).contains("lora")
                    && !h.contains("复现")) {
                return;
            }
            h = "HypeLoRA 复现";
        }
        String portfolioTitle = inferPortfolioTitle(h.isBlank() ? "HypeLoRA 复现" : h);
        if (portfolioTitle == null || portfolioTitle.isBlank()
                || isEphemeralTitle(portfolioTitle) || isNoiseTitle(portfolioTitle)
                || isTickJunkTitle(portfolioTitle)) {
            return;
        }
        ProjectRecord p = ensureProgramForHint(uid, portfolioTitle);
        String label = hint == null ? "" : hint.trim()
                .replaceFirst("(?i)^#+\\s*", "")
                .replaceFirst("(?i)^tick\\s*#?\\d+\\s*[·•\\-:]?\\s*", "");
        if (!label.isBlank() && !isTickJunkTitle(label)) {
            p.getMeta().put("lastTaskTitle", trim(label, 80));
        }
        touch(p);
        save();
    }

    public synchronized ProjectRecord ensureForPaper(String userId, String paperId, String titleHint, String domain) {
        String uid = blankToDefault(userId);
        Optional<ProjectRecord> existing = projects.stream()
                .filter(p -> uid.equals(p.getUserId()) && "paper".equals(p.getSourceType()) && paperId.equals(p.getSourceId()))
                .findFirst();
        if (existing.isPresent()) {
            touch(existing.get());
            save();
            return existing.get();
        }
        String title = (titleHint != null && !titleHint.isBlank()) ? trim(titleHint, 48)
                : ("论文 · " + (domain == null ? paperId : domain));
        ProjectRecord created = create(uid, title, "paper", paperId, null);
        ProjectRecord program = ensureProgramForHint(uid, title + " " + (domain == null ? "" : domain));
        created.getMeta().put("internal", true);
        created.getMeta().put("parentId", program.getId());
        created.getMeta().put("userFacing", false);
        created.setThreadId(program.getThreadId());
        save();
        return program;
    }

    public synchronized void touchProject(String projectId) {
        get(projectId).ifPresent(p -> {
            touch(p);
            save();
        });
    }

    public Map<String, Object> toView(ProjectRecord p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("sourceType", p.getSourceType());
        m.put("sourceId", p.getSourceId());
        m.put("threadId", p.getThreadId());
        m.put("userId", p.getUserId());
        m.put("contactId", blankContact(p.getContactId()));
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        m.put("linkedTaskIds", p.getLinkedTaskIds());
        m.put("meta", p.getMeta());
        return m;
    }

    /**
     * Resolve {@code @任务名} / {@code @「任务名」} / {@code @projectId} from a chat message.
     * Used so one daily dialogue can focus a project without switching threads.
     */
    public Optional<ProjectRecord> resolveMention(String userId, String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@「([^」]+)」|@\"([^\"]+)\"|@(\\S+)")
                .matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        String token = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String needle = token.trim();
        String uid = blankToDefault(userId);
        // Prefer user-facing dock projects (portfolios), never internal task rows
        Optional<ProjectRecord> byId = get(needle).filter(this::isUserFacing);
        if (byId.isPresent()) {
            return byId;
        }
        List<ProjectRecord> work = listDock(uid);
        for (ProjectRecord p : work) {
            if (needle.equalsIgnoreCase(p.getTitle()) || needle.equalsIgnoreCase(p.getId())) {
                return Optional.of(p);
            }
        }
        for (ProjectRecord p : work) {
            String t = p.getTitle() == null ? "" : p.getTitle();
            if (!t.isBlank() && (t.contains(needle) || needle.contains(t))) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private ProjectRecord create(String uid, String title, String sourceType, String sourceId, String assignee) {
        return create(uid, "ekina", title, sourceType, sourceId, assignee);
    }

    private ProjectRecord create(String uid, String contactId, String title, String sourceType, String sourceId,
                                 String assignee) {
        ProjectRecord p = new ProjectRecord();
        String id;
        if (sourceId != null && !sourceId.isBlank()) {
            id = sourceId.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        } else {
            id = sourceType + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        // avoid colliding with daily
        if (ChatSessionContext.DAILY.equals(id)) {
            id = sourceType + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        p.setId(id);
        p.setTitle(title);
        p.setSourceType(sourceType);
        p.setSourceId(sourceId);
        p.setThreadId(id); // 1 project = 1 default thread
        p.setUserId(uid);
        p.setContactId(blankContact(contactId));
        String now = Instant.now().toString();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        if (sourceId != null && "task".equals(sourceType)) {
            p.getLinkedTaskIds().add(sourceId);
        }
        if (assignee != null) {
            p.getMeta().put("assignee", assignee);
        }
        projects.add(0, p);
        save();
        return p;
    }

    private void touch(ProjectRecord p) {
        p.setUpdatedAt(Instant.now().toString());
    }

    private void migrateLegacyChannel(String userId) {
        try {
            Path legacy = Path.of(sandboxDir, "channel", userId + ".jsonl");
            Path daily = Path.of(sandboxDir, "channel", userId, ChatSessionContext.DAILY + ".jsonl");
            if (!Files.isRegularFile(legacy)) return;
            if (Files.isRegularFile(daily) && Files.size(daily) > 0) return;
            Files.createDirectories(daily.getParent());
            Files.copy(legacy, daily);
            // mark messages with threadId when ChannelService reads — file copy is enough
            System.out.println("[ProjectService] migrated legacy channel → daily for " + userId);
            touch(ensureDaily(userId));
            save();
        } catch (Exception e) {
            System.err.println("[ProjectService] migrate failed: " + e.getMessage());
        }
    }

    private synchronized void load() {
        projects.clear();
        try {
            Path f = indexFile();
            if (!Files.isRegularFile(f)) return;
            String json = Files.readString(f, StandardCharsets.UTF_8);
            List<ProjectRecord> list = mapper.readValue(json, new TypeReference<>() {});
            if (list != null) projects.addAll(list);
            // Migrate: legacy rows without contactId belong to ekina
            boolean dirty = false;
            for (ProjectRecord p : projects) {
                if (p.getContactId() == null || p.getContactId().isBlank()) {
                    p.setContactId("ekina");
                    dirty = true;
                }
            }
            if (dirty) {
                save();
                System.out.println("[ProjectService] migrated contactId=ekina for legacy projects");
            }
        } catch (Exception e) {
            System.err.println("[ProjectService] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(root());
            Path temporary = Files.createTempFile(root(), "projects-", ".tmp");
            try {
                Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projects), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, indexFile(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, indexFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception e) {
            throw new IllegalStateException("项目索引保存失败", e);
        }
    }

    private static String blankToDefault(String userId) {
        return (userId == null || userId.isBlank()) ? "user-1" : userId;
    }

    private static String blankContact(String contactId) {
        return (contactId == null || contactId.isBlank()) ? "ekina" : contactId.trim();
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(0, n - 1) + "…";
    }
}
