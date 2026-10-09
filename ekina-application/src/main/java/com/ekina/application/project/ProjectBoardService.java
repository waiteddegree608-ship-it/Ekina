package com.ekina.application.project;

import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.multiagent.Mission;
import com.ekina.application.multiagent.MissionNode;
import com.ekina.application.paper.PaperProject;
import com.ekina.application.paper.PaperStage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Owner-facing research / work board per project.
 * This is the human-readable plan (now / next / done / findings / timeline),
 * not the internal Mission DAG or paper stage machine.
 */
@Service
public class ProjectBoardService {

    private static final int MAX_TIMELINE = 80;
    private static final int MAX_DONE = 40;
    private static final int MAX_FINDINGS = 40;
    private static final int MAX_NEXT = 16;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ProjectService projectService;

    public synchronized Map<String, Object> get(String projectId) {
        Optional<ProjectRecord> opt = projectService.get(projectId);
        if (opt.isEmpty()) {
            Map<String, Object> err = emptyBoard(projectId);
            err.put("error", "not found");
            return err;
        }
        Map<String, Object> board = loadOrCreate(opt.get());
        return present(opt.get(), board);
    }

    public synchronized Map<String, Object> upsert(String projectId, Map<String, Object> patch) {
        ProjectRecord p = projectService.get(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId));
        Map<String, Object> board = loadOrCreate(p);
        if (patch != null) {
            if (patch.containsKey("researchUpdate"))
                board.put("researchLog", ResearchLedger.append(board.get("researchLog"), patch.get("researchUpdate")));
            // Owner collaboration settings and guidance are deliberately not agent-writable.
            if (patch.containsKey("roadmap")) board.put("roadmap", ResearchPlan.roadmap(patch.get("roadmap")));
            if (patch.containsKey("decisions")) board.put("decisions", ResearchPlan.decisions(patch.get("decisions")));
            if (patch.containsKey("nextDeliverable")) board.put("nextDeliverable", ResearchPlan.text(patch.get("nextDeliverable"), 1000));
            if (patch.containsKey("guidanceReply")) {
                Map<?, ?> reply = ResearchPlan.object(patch.get("guidanceReply"));
                String id = ResearchPlan.required(reply.get("id"), 80);
                String effect = ResearchPlan.required(reply.get("effect"), 2000);
                List<Map<String, Object>> guidance = listOfMaps(board.get("guidance"));
                Map<String, Object> item = guidance.stream().filter(g -> id.equals(g.get("id"))).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("指导记录不存在"));
                item.put("reply", effect);
                item.put("repliedAt", Instant.now().toString());
                item.put("status", "responded");
                board.put("guidance", guidance);
            }
            copyIfString(patch, board, "goal");
            copyIfString(patch, board, "status");
            copyIfString(patch, board, "now");
            if (patch.get("next") instanceof List<?> next) {
                board.put("next", clipStrings(next, MAX_NEXT));
            }
            if (patch.get("questions") instanceof List<?> q) {
                board.put("questions", clipStrings(q, 12));
            }
            if (patch.get("done") instanceof List<?> done) {
                board.put("done", clipMaps(done, MAX_DONE));
            }
            if (patch.get("findings") instanceof List<?> f) {
                board.put("findings", clipMaps(f, MAX_FINDINGS));
            }
        }
        board.put("updatedAt", Instant.now().toString());
        save(p.getId(), board);
        projectService.touchProject(p.getId());
        return present(p, loadOrCreate(p));
    }

    public synchronized Map<String, Object> appendEvent(String projectId, String title, String detail, String status) {
        ProjectRecord p = projectService.get(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId));
        Map<String, Object> board = loadOrCreate(p);
        String st = normalizeStatus(status);
        String t = title == null ? "" : title.trim();
        String d = detail == null ? "" : detail.trim();
        if (t.isBlank()) {
            t = d.isBlank() ? "进度" : clip(d, 48);
        }

        List<Map<String, Object>> timeline = listOfMaps(board.get("timeline"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("at", Instant.now().toString());
        ev.put("title", clip(t, 80));
        ev.put("detail", clip(d, 400));
        ev.put("status", st);
        timeline.add(0, ev);
        if (timeline.size() > MAX_TIMELINE) {
            timeline = new ArrayList<>(timeline.subList(0, MAX_TIMELINE));
        }
        board.put("timeline", timeline);

        if ("running".equals(st) || "queued".equals(st)) {
            board.put("now", clip(t, 80));
            board.put("status", "running");
        } else if ("succeeded".equals(st) || "done".equals(st)) {
            List<Map<String, Object>> done = listOfMaps(board.get("done"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", clip(t, 80));
            item.put("result", clip(d, 240));
            item.put("at", Instant.now().toString());
            done.add(0, item);
            if (done.size() > MAX_DONE) {
                done = new ArrayList<>(done.subList(0, MAX_DONE));
            }
            board.put("done", done);
            List<String> next = listOfStrings(board.get("next"));
            final String eventTitle = t;
            next.removeIf(x -> x != null && (x.equals(eventTitle) || eventTitle.contains(x) || x.contains(eventTitle)));
            board.put("next", next);
            if (!"blocked".equals(String.valueOf(board.get("status")))) {
                board.put("status", next.isEmpty() ? "running" : "running");
            }
        } else if ("failed".equals(st) || "blocked".equals(st)) {
            List<Map<String, Object>> findings = listOfMaps(board.get("findings"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "fail");
            item.put("text", clip((t + (d.isBlank() ? "" : " — " + d)), 320));
            item.put("at", Instant.now().toString());
            findings.add(0, item);
            if (findings.size() > MAX_FINDINGS) {
                findings = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
            }
            board.put("findings", findings);
            board.put("status", "blocked");
            board.put("now", clip("卡住：" + t, 80));
        } else if ("insight".equals(st)) {
            List<Map<String, Object>> findings = listOfMaps(board.get("findings"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "insight");
            item.put("text", clip((t + (d.isBlank() ? "" : " — " + d)), 320));
            item.put("at", Instant.now().toString());
            findings.add(0, item);
            board.put("findings", findings);
        }

        board.put("updatedAt", Instant.now().toString());
        save(p.getId(), board);
        projectService.touchProject(p.getId());
        return present(p, loadOrCreate(p));
    }

    public synchronized void seedPaper(ProjectRecord project, PaperProject paper) {
        if (project == null) return;
        Map<String, Object> board = loadOrCreate(project);
        if (isBlank(board.get("goal"))) {
            String goal = paper.getTitleWorking();
            if (goal == null || goal.isBlank()) goal = paper.getDomain();
            board.put("goal", clip(goal, 160));
        }
        board.put("status", "running");
        board.put("now", "选题与文献摸底");
        if (listOfStrings(board.get("next")).isEmpty()) {
            board.put("next", List.of(
                    "锁定可发表的问题与贡献形状",
                    "检索近两年相关工作，杀掉已做过的 idea",
                    "定 baseline 与实验协议",
                    "跑实验并对照数据写结论"
            ));
        }
        board.put("updatedAt", Instant.now().toString());
        save(project.getId(), board);
    }

    public synchronized void syncPaperStage(ProjectRecord project, PaperProject paper, PaperStage stage, String resultNote) {
        if (project == null || stage == null) return;
        String title = paperStageTitle(stage);
        String status = "succeeded";
        if (stage == PaperStage.FAILED || "failed".equalsIgnoreCase(paper.getStatus())) {
            status = "failed";
        } else if (stage == PaperStage.GATE && paper.getGateReport() != null
                && Boolean.FALSE.equals(paper.getGateReport().get("pass"))) {
            status = "failed";
        }
        appendEvent(project.getId(), title, resultNote == null ? "" : resultNote, status);
        Map<String, Object> board = loadOrCreate(project);
        board.put("now", nextPaperNow(stage));
        save(project.getId(), board);
    }

    public synchronized void syncMission(ProjectRecord project, Mission mission) {
        if (project == null || mission == null) return;
        Map<String, Object> board = loadOrCreate(project);
        if (isBlank(board.get("goal")) && mission.getGoal() != null) {
            board.put("goal", clip(mission.getGoal(), 160));
        }
        List<String> next = new ArrayList<>();
        String now = null;
        String overall = "planning";
        int running = 0;
        int blocked = 0;
        int done = 0;
        for (MissionNode n : mission.getNodes()) {
            String st = n.getStatus() == null ? "pending" : n.getStatus().toLowerCase(Locale.ROOT);
            String title = n.getTitle() == null ? n.getId() : n.getTitle();
            if ("running".equals(st) || "queued".equals(st)) {
                running++;
                if (now == null) now = title;
            } else if ("pending".equals(st)) {
                next.add(title);
            } else if ("failed".equals(st) || "blocked".equals(st)) {
                blocked++;
            } else if ("succeeded".equals(st) || "done".equals(st)) {
                done++;
            }
        }
        if (running > 0) overall = "running";
        else if (blocked > 0) overall = "blocked";
        else if ("succeeded".equalsIgnoreCase(mission.getStatus())) overall = "done";
        else if ("failed".equalsIgnoreCase(mission.getStatus())) overall = "blocked";
        board.put("status", overall);
        if (now != null) board.put("now", clip(now, 80));
        if (!next.isEmpty()) board.put("next", clipStrings(next, MAX_NEXT));
        board.put("updatedAt", Instant.now().toString());
        save(project.getId(), board);
    }

    public synchronized List<Map<String, Object>> listSummaries(String userId, String contactId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectRecord p : projectService.listDock(userId, contactId)) {
            Map<String, Object> board = loadOrCreate(p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("title", p.getTitle());
            row.put("sourceType", p.getSourceType());
            row.put("status", board.getOrDefault("status", "idle"));
            row.put("now", board.getOrDefault("now", ""));
            row.put("goal", board.getOrDefault("goal", p.getTitle()));
            row.put("next", board.getOrDefault("next", List.of()));
            row.put("collaboration", board.get("collaboration"));
            row.put("pendingGuidance", guidanceSummary(board, true));
            row.put("decisions", board.get("decisions"));
            row.put("research", ResearchLedger.summary(board.get("researchLog")));
            out.add(row);
        }
        return out;
    }

    public synchronized String asPromptBlock(String userId, String contactId, String focusedProjectId) {
        List<Map<String, Object>> rows = listSummaries(userId, contactId);
        if (rows.isEmpty()) {
            return "";
        }
        rows = rows.stream().sorted(java.util.Comparator.comparingInt(r ->
                java.util.Objects.equals(focusedProjectId, r.get("id")) ? 0 : 1)).limit(12).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("\n[课题进度板 — 给主人看的计划，不是聊天流水账]\n");
        sb.append("- 推进课题时必须 updateProjectBoard / appendProjectEvent 更新这块板：现在做什么、下一步、做成了什么、发现/卡住。\n");
        sb.append("- 禁止在日常聊天里逐条罗列「我做了 A、又做了 B」。聊天只说需要主人知道的一句人话；细节写在板上。\n");
        sb.append("- 同时进行多个课题时，每个课题一块板，用 projectId 对号入座。\n");
        sb.append("- 每次推进前读取 getProjectBoard。协作 guided=重要研究判断先讨论；checkpoints=关键节点征求意见，其余在授权内推进；delegated=约定范围内自主推进。预算是规划约束，不是新增授权，也不是自动停费保证。\n");
        sb.append("- 把模糊方向变成研究问题；兴趣探索先核对原始来源并给比较报告，不要直接启动论文流水线。任何创新先查相近工作，再设计最小验证，允许回退或停止。\n");
        sb.append("- 用 roadmap 记录 purpose/deliverable/acceptance/condition/resources/dependsOn；确定与条件步骤分开。费用未知用 null，有估价必写 estimateBasis。nextDeliverable 写下一份可验收成果。\n");
        sb.append("- decisions.kind=consultation 是征求意见，仅在现有授权和参与约定允许时按建议继续；authorization 必须通过现有 requestPermission 等待明确授权，填写对应 permissionId；沉默不是授权。\n");
        sb.append("- 用户指导存于 guidance。处理后同时更新计划并用 guidanceReply:{id,effect} 说明具体影响；仅记录回复不代表任务已执行。不可覆盖用户 collaboration 或抹掉指导记录。\n");
        sb.append("- 研究事实使用 research.records：attempt 记录尝试及失败；evidence 记录来源、适用条件；review 记录独立检查及产物；conclusion 引用证据。reviewed 仅表示已有支持性审查记录，不代表事实已自动验证。不要把模型审查升级成独立复现。\n");
        sb.append("- 用 updateProjectBoard 的 researchUpdate:{expectedRevision,records:[...]} 追加不可改写的记录。先读 research.revision；id 稳定以便重试；冲突后重新读取。错误记录用 withdrawal:{id,type,targetId,reason} 撤回，再用新 id 补充。撤回和争议会传递至相关结论，写作前重新核对。\n");
        sb.append("- 下一轮用 checkpoint 记录 question,nextAction,continueWhen,stopWhen,resources,basisIds,costMin/costMax/currency/estimateBasis。费用未知=null；只提出能减少当前不确定性的最小验证。记录计划不会启动任务、授权或预留费用。needs_review/contested/withdrawn 的计划依据先复核；尊重用户约定再执行。\n");
        for (Map<String, Object> row : rows) {
            boolean focus = focusedProjectId != null && focusedProjectId.equals(row.get("id"));
            sb.append(focus ? "★ " : "- ");
            sb.append(row.get("title")).append(" (`").append(row.get("id")).append("`)");
            sb.append(" 状态=").append(row.get("status"));
            Object now = row.get("now");
            if (now != null && !String.valueOf(now).isBlank()) {
                sb.append(" 现在：").append(now);
            }
            Object next = row.get("next");
            if (next instanceof List<?> n && !n.isEmpty()) {
                sb.append(" 下一步：").append(n.get(0));
            }
            sb.append("\n");
            sb.append("  用户约定：").append(row.get("collaboration")).append("\n");
            sb.append("  待处理指导：").append(row.get("pendingGuidance")).append("\n");
            sb.append("  待讨论/授权：").append(row.get("decisions")).append("\n");
            if (focus) sb.append("  研究证据摘要（完整记录请读课题板）：").append(row.get("research")).append("\n");
            if (focus) sb.append("  最近指导及回应（完整内容请读课题板）：").append(guidanceSummary(get(focusedProjectId), false)).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> guidanceSummary(Map<String, Object> board, boolean pendingOnly) {
        return listOfMaps(board.get("guidance")).stream()
                .filter(g -> !pendingOnly || "pending".equals(g.get("status"))).limit(8)
                .map(g -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    for (String key : List.of("id", "target", "status", "text", "reply"))
                        if (g.get(key) != null) summary.put(key, clip(String.valueOf(g.get(key)), 600));
                    return summary;
                }).toList();
    }

    public synchronized void initializeResearch(String projectId, String brief, String entry, Map<String, Object> settings) {
        ProjectRecord p = projectService.get(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        Map<String, Object> board = loadOrCreate(p);
        if (Boolean.TRUE.equals(board.get("researchInitialized"))) return;
        board.put("collaboration", ResearchPlan.collaboration(settings));
        board.put("goal", brief);
        board.put("status", "planning");
        board.put("now", "等待开始调研");
        board.put("nextDeliverable", "review".equals(entry) ? "检索协议、来源清单、证据矩阵与综述草稿" : "explore".equals(entry) ? "原始来源核验、研究现状与技术比较报告" : "候选选题、相近工作与最小验证方案");
        board.put("guidance", List.of(Map.of("id", "initial-brief", "target", "研究起点 · " + entry,
                "text", brief, "at", Instant.now().toString(), "status", "pending")));
        board.put("researchInitialized", true);
        board.put("updatedAt", Instant.now().toString());
        save(projectId, board);
    }

    public synchronized Map<String, Object> setCollaboration(String projectId, Map<String, Object> settings) {
        ProjectRecord p = projectService.get(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        Map<String, Object> board = loadOrCreate(p);
        Map<String, Object> validated = ResearchPlan.collaboration(settings);
        board.put("collaboration", validated);
        addOwnerEvent(board, "更新参与约定", "参与方式：" + validated.get("mode") + "；规划预算：" + validated.get("budget") + " " + validated.get("currency"));
        board.put("updatedAt", Instant.now().toString());
        save(projectId, board);
        projectService.touchProject(projectId);
        return get(projectId);
    }

    public synchronized Map<String, Object> addGuidance(String projectId, String target, String text, String requestId) {
        ProjectRecord p = projectService.get(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        String content = ResearchPlan.required(text, 4000);
        String context = ResearchPlan.text(target, 240);
        String id = ResearchPlan.required(requestId, 80);
        Map<String, Object> board = loadOrCreate(p);
        List<Map<String, Object>> guidance = listOfMaps(board.get("guidance"));
        Map<String, Object> existing = guidance.stream().filter(g -> id.equals(g.get("id"))).findFirst().orElse(null);
        if (existing != null) {
            if (!content.equals(existing.get("text")) || !context.equals(existing.get("target")))
                throw new IllegalArgumentException("重复请求内容不一致");
            return get(projectId);
        }
        if (guidance.stream().filter(g -> "pending".equals(g.get("status"))).count() >= 24)
            throw new IllegalArgumentException("待处理指导过多，请先处理已有指导");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("target", context);
        item.put("text", content);
        item.put("at", Instant.now().toString());
        item.put("status", "pending");
        guidance.add(0, item);
        // Keep all pending instructions; remove only oldest answered entries.
        while (guidance.size() > 80) {
            int index = guidance.size() - 1;
            while (index >= 0 && "pending".equals(guidance.get(index).get("status"))) index--;
            if (index < 0) break;
            guidance.remove(index);
        }
        board.put("guidance", guidance);
        addOwnerEvent(board, "用户提交指导", context.isBlank() ? content : context + "：" + content);
        board.put("updatedAt", Instant.now().toString());
        save(projectId, board);
        projectService.touchProject(projectId);
        return get(projectId);
    }

    private void addOwnerEvent(Map<String, Object> board, String title, String detail) {
        List<Map<String, Object>> events = listOfMaps(board.get("timeline"));
        events.add(0, Map.of("at", Instant.now().toString(), "title", title, "detail", clip(detail, 2000), "status", "note"));
        board.put("timeline", events.subList(0, Math.min(events.size(), MAX_TIMELINE)));
    }

    public Optional<ProjectRecord> visibleForPaper(String userId, String paperId) {
        List<ProjectRecord> all = projectService.list(userId);
        Optional<ProjectRecord> paper = all.stream()
                .filter(p -> "paper".equals(p.getSourceType()) && paperId != null && paperId.equals(p.getSourceId()))
                .findFirst();
        if (paper.isPresent() && paper.get().getMeta() != null && paper.get().getMeta().get("parentId") != null) {
            String parent = String.valueOf(paper.get().getMeta().get("parentId"));
            Optional<ProjectRecord> prog = projectService.get(parent);
            if (prog.isPresent()) return prog;
        }
        return paper;
    }

    public Optional<ProjectRecord> visibleForMission(String userId, String missionId) {
        List<ProjectRecord> all = projectService.list(userId);
        for (ProjectRecord p : all) {
            if (!"portfolio".equals(p.getSourceType()) || p.getMeta() == null) continue;
            Object linked = p.getMeta().get("linkedMissionIds");
            if (linked instanceof List<?> list && list.stream().anyMatch(x -> missionId.equals(String.valueOf(x)))) {
                return Optional.of(p);
            }
        }
        return all.stream()
                .filter(p -> "mission".equals(p.getSourceType()) && missionId.equals(p.getSourceId()))
                .findFirst();
    }

    private Map<String, Object> loadOrCreate(ProjectRecord p) {
        Path file = fileOf(p.getId());
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> loaded = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeReference<>() {});
                if (loaded != null) {
                    return normalize(p, loaded);
                }
            } catch (Exception e) {
                throw new IllegalStateException("课题板读取失败，已保留原文件", e);
            }
        }
        Map<String, Object> fresh = emptyBoard(p.getId());
        fresh.put("goal", p.getTitle() == null ? "" : p.getTitle());
        save(p.getId(), fresh);
        return fresh;
    }

    private Map<String, Object> emptyBoard(String projectId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("projectId", projectId);
        b.put("goal", "");
        b.put("status", "idle");
        b.put("now", "");
        b.put("next", new ArrayList<String>());
        b.put("done", new ArrayList<Map<String, Object>>());
        b.put("findings", new ArrayList<Map<String, Object>>());
        b.put("timeline", new ArrayList<Map<String, Object>>());
        b.put("questions", new ArrayList<String>());
        b.put("roadmap", List.of());
        b.put("nextDeliverable", "");
        b.put("decisions", List.of());
        b.put("guidance", List.of());
        b.put("researchLog", List.of());
        b.put("collaboration", ResearchPlan.collaboration(Map.of()));
        b.put("updatedAt", Instant.now().toString());
        return b;
    }

    private Map<String, Object> normalize(ProjectRecord p, Map<String, Object> raw) {
        Map<String, Object> b = emptyBoard(p.getId());
        b.putAll(raw);
        b.put("projectId", p.getId());
        if (!(b.get("next") instanceof List<?>)) b.put("next", new ArrayList<String>());
        if (!(b.get("done") instanceof List<?>)) b.put("done", new ArrayList<>());
        if (!(b.get("findings") instanceof List<?>)) b.put("findings", new ArrayList<>());
        if (!(b.get("timeline") instanceof List<?>)) b.put("timeline", new ArrayList<>());
        if (!(b.get("questions") instanceof List<?>)) b.put("questions", new ArrayList<String>());
        return b;
    }

    private void save(String projectId, Map<String, Object> board) {
        try {
            Path file = fileOf(projectId);
            Files.createDirectories(file.getParent());
            Map<String, Object> copy = new LinkedHashMap<>(board);
            copy.remove("project");
            copy.remove("error");
            copy.remove("research");
            Path temporary = Files.createTempFile(file.getParent(), "board-", ".tmp");
            try {
                Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(copy), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception e) {
            throw new IllegalStateException("课题板保存失败，修改未确认", e);
        }
    }

    private Map<String, Object> present(ProjectRecord project, Map<String, Object> board) {
        Map<String, Object> out = new LinkedHashMap<>(board);
        out.put("research", ResearchLedger.view(out.remove("researchLog")));
        out.put("project", projectService.toView(project));
        return out;
    }

    private Path fileOf(String projectId) {
        return homeQuotaService.homePath()
                .resolve("workspace")
                .resolve("projects")
                .resolve(projectId)
                .resolve("board.json");
    }

    private static void copyIfString(Map<String, Object> from, Map<String, Object> to, String key) {
        Object v = from.get(key);
        if (v != null) {
            to.put(key, clip(String.valueOf(v), 240));
        }
    }

    private static List<String> clipStrings(List<?> in, int max) {
        List<String> out = new ArrayList<>();
        for (Object o : in) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (s.isBlank()) continue;
            out.add(clip(s, 120));
            if (out.size() >= max) break;
        }
        return out;
    }

    private static List<Map<String, Object>> clipMaps(List<?> in, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : in) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            }
            if (out.size() >= max) break;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static List<String> listOfStrings(Object raw) {
        if (raw instanceof List<?> list) {
            return clipStrings(list, MAX_NEXT);
        }
        return new ArrayList<>();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "note";
        String s = status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "running", "queued", "succeeded", "done", "failed", "blocked", "insight", "note" -> s;
            case "ok", "complete" -> "succeeded";
            case "error", "fail" -> "failed";
            case "stuck" -> "blocked";
            case "finding", "lesson" -> "insight";
            default -> "note";
        };
    }

    private static String paperStageTitle(PaperStage st) {
        if (st == null) return "论文阶段";
        return switch (st) {
            case SCOUT -> "选题";
            case LIT -> "文献";
            case REPRO -> "复现摸底";
            case SWEEP -> "实验";
            case COMPARE -> "对照比较";
            case ABLATE -> "消融";
            case ANALYZE -> "分析结果";
            case FIG -> "图表";
            case TEX -> "写作";
            case CITE_CHECK -> "引用核验";
            case PACK -> "打包";
            case GATE -> "诚实门禁";
            case DONE -> "完成";
            case FAILED -> "失败";
            default -> st.name();
        };
    }

    private static String nextPaperNow(PaperStage stage) {
        if (stage == null) return "";
        return switch (stage) {
            case SCOUT -> "读文献、判断 idea 是否已有人做过";
            case LIT -> "定 baseline 与实验协议";
            case REPRO -> "准备实验 / 上机";
            case SWEEP -> "看实验数字，对照假设";
            case COMPARE, ABLATE -> "整理对照与消融";
            case ANALYZE -> "按数据写结论（禁止越界）";
            case FIG -> "写正文 / 核引用";
            case TEX, CITE_CHECK -> "打包与诚实门禁";
            case PACK, GATE, DONE -> "等待主人过目";
            case FAILED -> "复盘失败原因";
            default -> "";
        };
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(0, n);
    }
}
