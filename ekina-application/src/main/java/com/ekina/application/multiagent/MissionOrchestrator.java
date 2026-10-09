package com.ekina.application.multiagent;

import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Schedules Mission DAGs: when a node's dependsOn are all succeeded, dispatch to CODER/CLAW.
 */
@Service
public class MissionOrchestrator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Mission> missions = new ArrayList<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private MultiAgentOrchestrator multiAgentOrchestrator;

    @Autowired
    private TaskGraphPlanner planner;

    @Autowired
    private com.ekina.application.project.ProjectService projectService;

    @Autowired
    private com.ekina.application.project.ProjectBoardService projectBoardService;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.multiagent.enabled:true}")
    private boolean enabled;

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    @PostConstruct
    public void init() {
        load();
    }

    public Mission accept(String userId, String goal, String nodesJson) {
        if (!enabled) {
            throw new IllegalStateException("multi-agent disabled");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        String uid = userId == null || userId.isBlank() ? defaultUser : userId;
        Mission m = planner.plan(uid, goal, nodesJson);
        persistMissionFiles(m);
        synchronized (this) {
            missions.add(0, m);
            save();
        }
        try {
            var proj = projectService.ensureForMission(uid, m.getId(), goal);
            try {
                projectBoardService.syncMission(proj, m);
            } catch (Exception be) {
                System.err.println("[Mission] seed board: " + be.getMessage());
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("missionId", m.getId());
            detail.put("projectId", proj.getId());
            detail.put("threadId", proj.getThreadId());
            detail.put("nodes", m.getNodes().size());
            detail.put("silent", true);
            channelService.appendToThread(uid, proj.getThreadId(), "system", "system",
                    "🎯 新任务图 mission=`" + m.getId() + "` nodes=" + m.getNodes().size()
                            + "\n目标：" + trim(goal, 200),
                    detail, "done");
            // One-shot work → chat notice (quotable), not a lasting dock "repo"
            if (com.ekina.application.project.ProjectService.isEphemeralTitle(goal)
                    || Boolean.TRUE.equals(proj.getMeta().get("ephemeral"))) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("kind", "notice");
                notice.put("ephemeral", true);
                notice.put("quotable", true);
                notice.put("missionId", m.getId());
                notice.put("contactId", com.ekina.application.contact.ContactRegistry.EKINA);
                notice.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_EKINA);
                channelService.appendToThread(uid, com.ekina.application.contact.ContactRegistry.THREAD_EKINA,
                        "agent", "chat",
                        "📝 " + trim(goal, 120) + "（进行中，可右键引用继续聊）",
                        notice, "done");
            }
        } catch (Exception e) {
            channelService.append(uid, "system", "system",
                    "🎯 新任务图 mission=`" + m.getId() + "` nodes=" + m.getNodes().size()
                            + "\n目标：" + trim(goal, 200),
                    Map.of("missionId", m.getId(), "nodes", m.getNodes().size(), "silent", true), "done");
        }
        return m;
    }

    /** Accept several independent goals as concurrent Missions (one project each). */
    public List<Mission> acceptMany(String userId, List<String> goals) {
        List<Mission> created = new ArrayList<>();
        if (goals == null) {
            return created;
        }
        for (String g : goals) {
            if (g == null || g.isBlank()) {
                continue;
            }
            created.add(accept(userId, g.trim(), null));
        }
        return created;
    }

    public synchronized int countActive(String userId) {
        String uid = userId == null || userId.isBlank() ? defaultUser : userId;
        int n = 0;
        for (Mission m : missions) {
            if (!uid.equals(m.getUserId())) {
                continue;
            }
            String st = m.getStatus() == null ? "" : m.getStatus().toLowerCase(Locale.ROOT);
            if ("running".equals(st) || "planning".equals(st)) {
                n++;
            }
        }
        return n;
    }

    public synchronized Optional<Mission> get(String id) {
        return missions.stream().filter(m -> id != null && id.equals(m.getId())).findFirst();
    }

    public synchronized List<Mission> list(String userId, int limit) {
        int n = Math.max(1, Math.min(50, limit));
        return missions.stream()
                .filter(m -> userId == null || userId.isBlank() || userId.equals(m.getUserId()))
                .sorted(Comparator.comparing(Mission::getCreatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public synchronized Mission cancel(String missionId) {
        Mission m = get(missionId).orElseThrow(() -> new IllegalArgumentException("mission not found: " + missionId));
        if ("succeeded".equalsIgnoreCase(m.getStatus()) || "failed".equalsIgnoreCase(m.getStatus())
                || "cancelled".equalsIgnoreCase(m.getStatus())) {
            return m;
        }
        m.setStatus("cancelled");
        m.setFinishedAt(Instant.now().toString());
        for (MissionNode node : m.getNodes()) {
            String st = node.getStatus() == null ? "" : node.getStatus().toLowerCase(Locale.ROOT);
            if ("pending".equals(st) || "queued".equals(st) || "running".equals(st) || "blocked".equals(st)) {
                node.setStatus("cancelled");
            }
        }
        save();
        persistMissionFiles(m);
        return m;
    }

    @Scheduled(fixedDelayString = "${ekina.multiagent.poll-ms:5000}")
    public void tick() {
        if (!enabled) return;
        List<Mission> active;
        synchronized (this) {
            active = missions.stream()
                    .filter(m -> "running".equalsIgnoreCase(m.getStatus()) || "planning".equalsIgnoreCase(m.getStatus()))
                    .collect(Collectors.toList());
        }
        for (Mission m : active) {
            try {
                advance(m);
            } catch (Exception e) {
                System.err.println("[Mission] advance failed " + m.getId() + ": " + e.getMessage());
            }
        }
    }

    private void advance(Mission mission) {
        boolean dirty = false;
        // Sync node status from underlying agent tasks
        for (MissionNode node : mission.getNodes()) {
            if (node.getTaskId() != null && !node.getTaskId().isBlank()) {
                Optional<AgentTask> opt = multiAgentOrchestrator.get(node.getTaskId());
                if (opt.isPresent()) {
                    AgentTask t = opt.get();
                    String st = t.getStatus() == null ? "" : t.getStatus().toLowerCase(Locale.ROOT);
                    if (!st.equalsIgnoreCase(nullTo(node.getStatus(), ""))) {
                        node.setStatus(st);
                        node.setResult(t.getResult());
                        node.setError(t.getError());
                        dirty = true;
                    }
                }
            }
        }

        // Block dependents of failed nodes
        Map<String, MissionNode> byId = new LinkedHashMap<>();
        for (MissionNode n : mission.getNodes()) {
            byId.put(n.getId(), n);
        }
        for (MissionNode node : mission.getNodes()) {
            String st = nullTo(node.getStatus(), "pending").toLowerCase(Locale.ROOT);
            if ("pending".equals(st) || "blocked".equals(st)) {
                boolean depFailed = false;
                boolean depsReady = true;
                for (String dep : node.getDependsOn()) {
                    MissionNode d = byId.get(dep);
                    if (d == null) {
                        depsReady = false;
                        continue;
                    }
                    String ds = nullTo(d.getStatus(), "pending").toLowerCase(Locale.ROOT);
                    if ("failed".equals(ds) || "cancelled".equals(ds)) {
                        depFailed = true;
                    }
                    if (!"succeeded".equals(ds)) {
                        depsReady = false;
                    }
                }
                if (depFailed && !"blocked".equals(st)) {
                    node.setStatus("blocked");
                    node.setError("blocked by failed dependency");
                    dirty = true;
                } else if (depsReady && ("pending".equals(st) || "blocked".equals(st))) {
                    // Dispatch
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("missionId", mission.getId());
                    meta.put("nodeId", node.getId());
                    AgentTask task = multiAgentOrchestrator.dispatch(
                            mission.getUserId(),
                            node.getAssignee() == null ? "CODER" : node.getAssignee().name(),
                            "[" + mission.getId() + "/" + node.getId() + "] " + node.getTitle(),
                            node.getBrief(),
                            meta);
                    node.setTaskId(task.getId());
                    node.setStatus("queued");
                    dirty = true;
                }
            }
        }

        // Mission terminal?
        boolean allDone = true;
        boolean anyFailed = false;
        boolean anyBlocked = false;
        for (MissionNode node : mission.getNodes()) {
            String st = nullTo(node.getStatus(), "pending").toLowerCase(Locale.ROOT);
            if ("succeeded".equals(st)) continue;
            if ("failed".equals(st)) {
                anyFailed = true;
                continue;
            }
            if ("blocked".equals(st) || "cancelled".equals(st)) {
                anyBlocked = true;
                continue;
            }
            allDone = false;
        }
        if (allDone) {
            String newStatus = (anyFailed || anyBlocked) ? "failed" : "succeeded";
            if (!newStatus.equalsIgnoreCase(mission.getStatus())) {
                mission.setStatus(newStatus);
                mission.setFinishedAt(Instant.now().toString());
                mission.setSummary(buildSummary(mission));
                dirty = true;
                writeDiary(mission);
                notifyMissionFinished(mission, newStatus);
            }
        }

        if (dirty) {
            synchronized (this) {
                save();
            }
            try {
                projectBoardService.visibleForMission(mission.getUserId(), mission.getId())
                        .ifPresent(proj -> projectBoardService.syncMission(proj, mission));
            } catch (Exception be) {
                System.err.println("[Mission] board sync: " + be.getMessage());
            }
            persistMissionFiles(mission);
        }
    }

    private void notifyMissionFinished(Mission mission, String newStatus) {
        String uid = mission.getUserId();
        try {
            var proj = projectService.ensureForMission(uid, mission.getId(), mission.getGoal());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("missionId", mission.getId());
            detail.put("projectId", proj.getId());
            detail.put("threadId", proj.getThreadId());
            detail.put("status", newStatus);
            detail.put("silent", true);
            channelService.appendToThread(uid, proj.getThreadId(), "system", "system",
                    ("succeeded".equals(newStatus) ? "✅" : "❌")
                            + " 任务图完成 mission=`" + mission.getId() + "` status=" + newStatus
                            + "\n" + trim(mission.getSummary(), 500),
                    detail, "done");

            boolean ephemeral = com.ekina.application.project.ProjectService.isEphemeralTitle(mission.getGoal())
                    || Boolean.TRUE.equals(proj.getMeta().get("ephemeral"));
            if (ephemeral) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("kind", "notice");
                notice.put("ephemeral", true);
                notice.put("quotable", true);
                notice.put("missionId", mission.getId());
                notice.put("status", newStatus);
                notice.put("contactId", com.ekina.application.contact.ContactRegistry.EKINA);
                notice.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_EKINA);
                String emoji = "succeeded".equals(newStatus) ? "✅" : "❌";
                channelService.appendToThread(uid, com.ekina.application.contact.ContactRegistry.THREAD_EKINA,
                        "agent", "chat",
                        emoji + " " + trim(mission.getGoal(), 100)
                                + ("succeeded".equals(newStatus) ? " · 完成" : " · " + newStatus)
                                + "（可右键引用继续聊）",
                        notice, "done");
            } else {
                // Refresh lasting project card freshness
                try {
                    projectService.noteProgress(uid, mission.getGoal());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[Mission] finish notify failed: " + e.getMessage());
        }
    }

    private String buildSummary(Mission m) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(m.getGoal()).append('\n');
        for (MissionNode n : m.getNodes()) {
            sb.append("- ").append(n.getId()).append(" [").append(n.getAssignee()).append("] ")
                    .append(n.getTitle()).append(" → ").append(n.getStatus());
            if (n.getTaskId() != null) sb.append(" (").append(n.getTaskId()).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }

    private void writeDiary(Mission m) {
        try {
            Path diaryDir = Path.of(agentHome, "diary");
            Files.createDirectories(diaryDir);
            String day = LocalDate.now(ZoneId.systemDefault()).toString();
            Path f = diaryDir.resolve(day + ".md");
            String entry = "\n\n## Mission " + m.getId() + " · " + Instant.now() + "\n\n"
                    + "- status: **" + m.getStatus() + "**\n"
                    + "- goal: " + m.getGoal() + "\n"
                    + "```\n" + trim(m.getSummary(), 2000) + "\n```\n";
            if (!Files.exists(f)) {
                Files.writeString(f, "# 日记 " + day + "\n" + entry, StandardCharsets.UTF_8);
            } else {
                Files.writeString(f, entry, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("[Mission] diary write failed: " + e.getMessage());
        }
    }

    private void persistMissionFiles(Mission m) {
        try {
            Path dir = Path.of(agentHome, "missions", m.getId());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("mission.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m),
                    StandardCharsets.UTF_8);
            for (MissionNode n : m.getNodes()) {
                Path nd = dir.resolve("nodes").resolve(n.getId());
                Files.createDirectories(nd);
                Files.writeString(nd.resolve("node.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[Mission] persist failed: " + e.getMessage());
        }
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("missions.json");
    }

    private synchronized void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            List<Mission> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            missions.clear();
            if (loaded != null) missions.addAll(loaded);
            // Resume interrupted running missions
            for (Mission m : missions) {
                if ("planning".equalsIgnoreCase(m.getStatus())) {
                    m.setStatus("running");
                }
            }
        } catch (Exception e) {
            System.err.println("[Mission] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(missions),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Mission] save failed: " + e.getMessage());
        }
    }

    private static String nullTo(String s, String d) {
        return s == null ? d : s;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
