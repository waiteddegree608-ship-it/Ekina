package com.ekina.application.project;

import com.ekina.application.multiagent.AgentTask;
import com.ekina.application.multiagent.Mission;
import com.ekina.application.multiagent.MissionNode;
import com.ekina.application.multiagent.MissionOrchestrator;
import com.ekina.application.multiagent.MultiAgentOrchestrator;
import com.ekina.application.paper.PaperOrchestrator;
import com.ekina.application.paper.PaperProject;
import com.ekina.application.paper.PaperStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates Mission nodes / linked AgentTasks into a timeline.
 * For portfolios, steps are Ekina's internal ledger; the dock only shows summary + latest.
 */
@Service
public class ProjectTimelineService {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MissionOrchestrator missionOrchestrator;

    @Autowired
    private MultiAgentOrchestrator multiAgentOrchestrator;

    @Autowired
    private PaperOrchestrator paperOrchestrator;

    @Autowired
    private ProjectBoardService projectBoardService;

    private static final PaperStage[] PAPER_PIPELINE = {
            PaperStage.SCOUT,
            PaperStage.LIT,
            PaperStage.REPRO,
            PaperStage.SWEEP,
            PaperStage.FIG,
            PaperStage.TEX,
            PaperStage.CITE_CHECK,
            PaperStage.PACK,
            PaperStage.GATE,
    };

    public Map<String, Object> timeline(String projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<ProjectRecord> opt = projectService.get(projectId);
        if (opt.isEmpty()) {
            out.put("error", "not found");
            out.put("id", projectId);
            return out;
        }
        ProjectRecord p = opt.get();
        out.put("project", projectService.toView(p));

        List<Map<String, Object>> steps = new ArrayList<>();
        String note = "";
        String stype = p.getSourceType() == null ? "" : p.getSourceType().toLowerCase(Locale.ROOT);

        if ("portfolio".equals(stype)) {
            note = "内部步骤供姬娜编排；老板只看项目卡进度。";
            Object linked = p.getMeta() == null ? null : p.getMeta().get("linkedMissionIds");
            if (linked instanceof List<?> mids) {
                for (Object mid : mids) {
                    missionOrchestrator.get(String.valueOf(mid)).ifPresent(m -> {
                        out.putIfAbsent("missionId", m.getId());
                        out.putIfAbsent("missionStatus", m.getStatus());
                        out.putIfAbsent("goal", m.getGoal());
                        for (MissionNode n : m.getNodes()) {
                            steps.add(stepFromNode(n));
                        }
                    });
                }
            }
            for (String tid : p.getLinkedTaskIds() == null ? List.<String>of() : p.getLinkedTaskIds()) {
                multiAgentOrchestrator.get(tid).ifPresent(t -> steps.add(stepFromTask(t)));
            }
            Object last = p.getMeta() == null ? null : p.getMeta().get("lastTaskTitle");
            if (last != null) {
                out.put("latestHint", String.valueOf(last));
            }
        } else if ("mission".equals(stype) && p.getSourceId() != null) {
            Optional<Mission> mOpt = missionOrchestrator.get(p.getSourceId());
            if (mOpt.isPresent()) {
                Mission m = mOpt.get();
                out.put("missionId", m.getId());
                out.put("missionStatus", m.getStatus());
                out.put("goal", m.getGoal());
                for (MissionNode n : m.getNodes()) {
                    steps.add(stepFromNode(n));
                }
            } else {
                note = "mission record missing: " + p.getSourceId();
            }
        } else if ("task".equals(stype)) {
            note = "内部任务账本（不对老板展示为独立项目）。";
            List<String> ids = p.getLinkedTaskIds() == null ? List.of() : p.getLinkedTaskIds();
            if (ids.isEmpty() && p.getSourceId() != null) {
                ids = List.of(p.getSourceId());
            }
            for (String tid : ids) {
                Optional<AgentTask> tOpt = multiAgentOrchestrator.get(tid);
                if (tOpt.isPresent()) {
                    steps.add(stepFromTask(tOpt.get()));
                }
            }
        } else if ("daily".equals(stype)) {
            note = "日常会话；项目进度见右侧项目卡。";
        } else if ("paper".equals(stype)) {
            String paperId = p.getSourceId() != null ? p.getSourceId() : p.getId();
            Optional<PaperProject> paperOpt = paperOrchestrator.get(paperId);
            if (paperOpt.isPresent()) {
                PaperProject paper = paperOpt.get();
                out.put("paperId", paper.getId());
                out.put("missionStatus", paper.getStatus());
                out.put("goal", paper.getTitleWorking());
                out.put("paperMode", paper.getMode());
                out.put("paperStage", paper.getStage() == null ? "" : paper.getStage().name());
                steps.addAll(stepsFromPaper(paper));
                Object honesty = paper.getGateReport() == null ? null : paper.getGateReport().get("honestyLevel");
                if (honesty != null) {
                    out.put("latestHint", "Gate · " + honesty);
                } else if (paper.getStage() != null) {
                    out.put("latestHint", "阶段 " + paper.getStage().name());
                }
                note = "token→论文流水线 · mode=" + (paper.getMode() == null ? "?" : paper.getMode());
            } else {
                note = "paper record missing: " + paperId;
            }
        } else {
            note = "unsupported sourceType: " + stype;
        }

        int done = 0;
        int running = 0;
        int blocked = 0;
        int total = steps.size();
        for (Map<String, Object> s : steps) {
            String st = String.valueOf(s.getOrDefault("status", "")).toLowerCase(Locale.ROOT);
            if ("succeeded".equals(st) || "done".equals(st) || "cancelled".equals(st)) {
                done++;
            } else if ("running".equals(st) || "queued".equals(st)) {
                running++;
            } else if ("blocked".equals(st) || "failed".equals(st)) {
                blocked++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("done", done);
        summary.put("total", total);
        summary.put("running", running);
        summary.put("blocked", blocked);

        out.put("steps", steps);
        out.put("summary", summary);
        out.put("internal", "portfolio".equals(stype) || "task".equals(stype));
        if (!note.isBlank()) {
            out.put("note", note);
        }
        try {
            Map<String, Object> board = projectBoardService.get(p.getId());
            out.put("board", board);
            Object now = board.get("now");
            if (now != null && !String.valueOf(now).isBlank() && !out.containsKey("latestHint")) {
                out.put("latestHint", String.valueOf(now));
            }
        } catch (Exception unavailable) {
            out.put("board", Map.of("projectId", projectId, "error", "课题板读取失败，原记录已保留；请稍后重试。"));
        }
        return out;
    }

    private static List<Map<String, Object>> stepsFromPaper(PaperProject paper) {
        List<Map<String, Object>> steps = new ArrayList<>();
        PaperStage current = paper.getStage() == null ? PaperStage.SCOUT : paper.getStage();
        boolean failed = current == PaperStage.FAILED || "failed".equalsIgnoreCase(paper.getStatus());
        boolean doneAll = current == PaperStage.DONE || "succeeded".equalsIgnoreCase(paper.getStatus());
        int curOrd = stageOrdinal(current);
        for (PaperStage st : PAPER_PIPELINE) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", "paper-" + st.name());
            s.put("title", paperStageTitle(st));
            s.put("assignee", "EKINA");
            s.put("dependsOn", List.of());
            int ord = stageOrdinal(st);
            String status;
            if (doneAll) {
                status = "succeeded";
            } else if (failed && ord == curOrd) {
                status = "failed";
            } else if (failed && ord > curOrd) {
                status = "pending";
            } else if (ord < curOrd) {
                status = "succeeded";
            } else if (ord == curOrd) {
                status = "running".equalsIgnoreCase(paper.getStatus()) ? "running" : "queued";
            } else {
                status = "pending";
            }
            // GATE special: if finished with gateReport.pass
            if (st == PaperStage.GATE && paper.getGateReport() != null && paper.getGateReport().get("pass") != null) {
                boolean pass = Boolean.TRUE.equals(paper.getGateReport().get("pass"));
                if (doneAll || failed || current == PaperStage.GATE || current == PaperStage.DONE || current == PaperStage.FAILED) {
                    status = pass ? "succeeded" : "failed";
                }
            }
            s.put("status", status);
            s.put("updatedAt", paper.getUpdatedAt());
            if ("failed".equals(status) && paper.getGateReport() != null) {
                Object detail = paper.getGateReport().get("honestyLevel");
                if (detail != null) {
                    s.put("error", String.valueOf(detail));
                }
            }
            steps.add(s);
        }
        return steps;
    }

    private static int stageOrdinal(PaperStage st) {
        if (st == null) return 0;
        if (st == PaperStage.DONE) return PAPER_PIPELINE.length;
        if (st == PaperStage.FAILED) return Math.max(0, PAPER_PIPELINE.length - 1);
        for (int i = 0; i < PAPER_PIPELINE.length; i++) {
            if (PAPER_PIPELINE[i] == st) return i;
        }
        return 0;
    }

    private static String paperStageTitle(PaperStage st) {
        if (st == null) return "";
        if (st == PaperStage.SCOUT) return "选题 SCOUT";
        if (st == PaperStage.LIT) return "文献 LIT";
        if (st == PaperStage.REPRO) return "复现 REPRO";
        if (st == PaperStage.SWEEP) return "实验 SWEEP";
        if (st == PaperStage.FIG) return "图表 FIG";
        if (st == PaperStage.TEX) return "写作 TEX";
        if (st == PaperStage.CITE_CHECK) return "引用核验";
        if (st == PaperStage.PACK) return "打包 PACK";
        if (st == PaperStage.GATE) return "诚实门禁 GATE";
        return st.name();
    }

    private static Map<String, Object> stepFromNode(MissionNode n) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", n.getId());
        s.put("title", n.getTitle() == null ? n.getId() : n.getTitle());
        s.put("assignee", n.getAssignee() == null ? "" : n.getAssignee().name());
        s.put("status", n.getStatus() == null ? "pending" : n.getStatus());
        s.put("taskId", n.getTaskId());
        s.put("dependsOn", n.getDependsOn() == null ? List.of() : n.getDependsOn());
        Object updated = n.getMeta() == null ? null : n.getMeta().get("updatedAt");
        s.put("updatedAt", updated);
        if (n.getError() != null && !n.getError().isBlank()) {
            s.put("error", n.getError());
        }
        return s;
    }

    private static Map<String, Object> stepFromTask(AgentTask t) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", t.getId());
        s.put("title", t.getTitle() == null ? t.getId() : t.getTitle());
        s.put("assignee", t.getAssignee() == null ? "" : t.getAssignee().name());
        s.put("status", t.getStatus() == null ? "pending" : t.getStatus());
        s.put("taskId", t.getId());
        s.put("dependsOn", List.of());
        s.put("updatedAt", t.getFinishedAt() != null ? t.getFinishedAt() : t.getStartedAt());
        if (t.getError() != null && !t.getError().isBlank()) {
            s.put("error", t.getError());
        }
        return s;
    }
}
