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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chief (伊姬娜) dispatches work to CODER (OpenCode) / CLAW (OpenClaw).
 * All three may share the same OpenCode Zen API key — {@link SharedApiBudget} limits local concurrency.
 */
@Service
public class MultiAgentOrchestrator {

    private static final Pattern EXIT_CODE = Pattern.compile("(?:OpenCode|OpenClaw CLI) exit=(\\d+)");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AgentTask> tasks = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(12, r -> {
        Thread t = new Thread(r, "ekina-worker");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private OpenCodeWorker openCodeWorker;

    @Autowired
    private OpenClawWorker openClawWorker;

    @Autowired
    private SharedApiBudget apiBudget;

    @Autowired
    private ProcessRegistry processRegistry;

    @Autowired
    private com.ekina.application.project.ProjectService projectService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.ekina.application.autonomy.AutonomyService autonomyService;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.multiagent.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void init() {
        load();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("queued", countStatus("queued"));
        m.put("running", countStatus("running"));
        m.put("succeeded", countStatus("succeeded"));
        m.put("failed", countStatus("failed"));
        m.put("cancelled", countStatus("cancelled"));
        m.put("apiInFlight", apiBudget.getInFlight());
        m.put("apiAvailableSlots", apiBudget.getAvailablePermits());
        m.put("apiTotalAcquired", apiBudget.getTotalAcquired());
        m.put("liveProcesses", processRegistry.size());
        m.put("crew", List.of(
                Map.of("role", "CHIEF", "name", "伊姬娜", "job", "总管：生活/日记/提醒/派活/汇报"),
                Map.of("role", "CODER", "name", "OpenCode 手", "job", "编程/SSH/训练/改代码"),
                Map.of("role", "CLAW", "name", "OpenClaw 触手", "job", "通道/本机自动化/对外消息")
        ));
        return m;
    }

    public synchronized AgentTask dispatch(String userId, String assigneeRaw, String title, String brief,
                                           Map<String, Object> meta) {
        if (!enabled) {
            throw new IllegalStateException("multi-agent disabled");
        }
        AgentRole role = parseRole(assigneeRaw);
        if (role == AgentRole.CHIEF) {
            throw new IllegalArgumentException("Cannot dispatch to CHIEF — she is the dispatcher");
        }
        AgentTask t = new AgentTask();
        t.setId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        t.setUserId(userId == null || userId.isBlank() ? defaultUser : userId);
        t.setAssignee(role);
        t.setTitle(title == null || title.isBlank() ? "untitled" : title.trim());
        t.setBrief(brief == null ? "" : brief.trim());
        t.setStatus("queued");
        t.setCreatedAt(Instant.now().toString());
        t.setMeta(meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(meta));
        try {
            String parentId = null;
            Object mid = t.getMeta().get("missionId");
            Object pid = t.getMeta().get("projectId");
            if (pid != null && !String.valueOf(pid).isBlank()) {
                parentId = String.valueOf(pid);
            } else if (mid != null && !String.valueOf(mid).isBlank()) {
                parentId = projectService.list(t.getUserId()).stream()
                        .filter(p -> "mission".equals(p.getSourceType()) && String.valueOf(mid).equals(p.getSourceId()))
                        .map(p -> {
                            Object parent = p.getMeta() == null ? null : p.getMeta().get("parentId");
                            return parent != null ? String.valueOf(parent) : p.getId();
                        })
                        .findFirst()
                        .orElse(null);
            }
            // Attach under portfolio — never create a user-facing card per micro-task
            var proj = projectService.ensureForTask(t.getUserId(), t.getId(), t.getTitle(), role.name(), parentId);
            t.getMeta().put("projectId", proj.getId());
            t.getMeta().put("threadId", proj.getThreadId());
            channelService.appendToThread(t.getUserId(), proj.getThreadId(), "system", "system",
                    "📋 总管派活 → " + role + "「" + t.getTitle() + "」 `" + t.getId() + "`",
                    Map.of("taskId", t.getId(), "assignee", role.name(),
                            "projectId", proj.getId(), "threadId", proj.getThreadId(),
                            "silent", true, "internal", true), "done");
        } catch (Exception e) {
            channelService.append(t.getUserId(), "system", "system",
                    "📋 总管派活 → " + role + "「" + t.getTitle() + "」 `" + t.getId() + "`",
                    Map.of("taskId", t.getId(), "assignee", role.name(), "silent", true), "done");
        }
        tasks.add(0, t);
        save();
        return t;
    }

    public synchronized List<AgentTask> list(String userId, int limit) {
        int n = Math.max(1, limit);
        return tasks.stream()
                .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                .sorted(Comparator.comparing(AgentTask::getCreatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public synchronized Optional<AgentTask> get(String id) {
        return tasks.stream().filter(t -> id != null && id.equals(t.getId())).findFirst();
    }

    /**
     * Cancel stuck queued/running tasks, kill live CLI process trees, and free API budget slots.
     */
    public synchronized Map<String, Object> cancelStuck(String userId, long olderThanSec) {
        long threshold = olderThanSec <= 0 ? 0 : Math.max(5, olderThanSec);
        Instant now = Instant.now();
        int cancelled = 0;
        int killed = 0;
        List<String> ids = new ArrayList<>();
        for (AgentTask t : tasks) {
            if (userId != null && !userId.isBlank() && !userId.equals(t.getUserId())) {
                continue;
            }
            String st = t.getStatus() == null ? "" : t.getStatus().toLowerCase(Locale.ROOT);
            if (!"running".equals(st) && !"queued".equals(st)) {
                continue;
            }
            Instant start = parseInstant(t.getStartedAt() != null ? t.getStartedAt() : t.getCreatedAt());
            if (threshold > 0 && start != null && now.getEpochSecond() - start.getEpochSecond() < threshold) {
                continue;
            }
            if (processRegistry.kill(t.getId())) {
                killed++;
            }
            t.setStatus("cancelled");
            t.setFinishedAt(now.toString());
            t.setError("cancelled stuck task by console (>" + threshold + "s)");
            if (t.getResult() == null || t.getResult().isBlank()) {
                t.setResult("cancelled stuck task");
            }
            cancelled++;
            ids.add(t.getId());
        }
        if (cancelled > 0) {
            save();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", cancelled);
        out.put("killedProcesses", killed);
        out.put("ids", ids);
        out.put("thresholdSec", threshold);
        out.put("apiAvailableSlots", apiBudget.getAvailablePermits());
        return out;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Scheduled(fixedDelayString = "${ekina.multiagent.poll-ms:5000}")
    public void pollQueue() {
        if (!enabled) return;
        List<AgentTask> toStart = new ArrayList<>();
        synchronized (this) {
            for (AgentTask t : tasks) {
                if ("queued".equalsIgnoreCase(t.getStatus())) {
                    t.setStatus("running");
                    t.setStartedAt(Instant.now().toString());
                    toStart.add(t);
                }
            }
            if (!toStart.isEmpty()) save();
        }
        for (AgentTask t : toStart) {
            pool.submit(() -> execute(t.getId()));
        }
    }

    private void execute(String taskId) {
        AgentTask snapshot;
        synchronized (this) {
            snapshot = get(taskId).orElse(null);
        }
        if (snapshot == null) return;
        // Skip if cancelled while waiting on the pool
        if ("cancelled".equalsIgnoreCase(snapshot.getStatus())) return;
        String result;
        try {
            if (snapshot.getAssignee() == AgentRole.CODER || snapshot.getAssignee() == AgentRole.SCRIBE) {
                result = openCodeWorker.run(snapshot, apiBudget, processRegistry);
            } else if (snapshot.getAssignee() == AgentRole.CLAW) {
                result = openClawWorker.run(snapshot, apiBudget, processRegistry);
            } else {
                result = "Unknown assignee";
            }
            String verdict = classifyResult(result);
            synchronized (this) {
                get(taskId).ifPresent(t -> {
                    // Don't overwrite a concurrent cancel
                    if ("cancelled".equalsIgnoreCase(t.getStatus())) {
                        return;
                    }
                    t.setFinishedAt(Instant.now().toString());
                    t.setResult(trim(result, 20_000));
                    t.setStatus(verdict);
                    if ("failed".equals(verdict)) {
                        t.setError(trim(result, 500));
                    } else {
                        t.setError(null);
                    }
                    if (t.getMeta() == null) {
                        t.setMeta(new LinkedHashMap<>());
                    }
                    if (snapshot.getMeta() != null) {
                        t.getMeta().putAll(snapshot.getMeta());
                    }
                    if (snapshot.getAssignee() == AgentRole.CODER || snapshot.getAssignee() == AgentRole.SCRIBE) {
                        String jid = t.getId();
                        t.getMeta().put("jobDir", openCodeWorker.jobDirFor(jid).toAbsolutePath().toString());
                        t.getMeta().put("reportDir", openCodeWorker.reportDirFor(jid).toAbsolutePath().toString());
                        t.getMeta().put("deliverDir", openCodeWorker.deliverDirFor(jid).toAbsolutePath().toString());
                    }
                    save();
                });
            }
            AgentTask done = get(taskId).orElse(snapshot);
            String threadId = done.getMeta() != null && done.getMeta().get("threadId") != null
                    ? String.valueOf(done.getMeta().get("threadId"))
                    : com.ekina.application.project.ChatSessionContext.DAILY;
            Map<String, Object> finishPayload = new LinkedHashMap<>();
            finishPayload.put("taskId", done.getId());
            finishPayload.put("status", done.getStatus());
            finishPayload.put("threadId", threadId);
            finishPayload.put("projectId", done.getMeta() != null
                    ? String.valueOf(done.getMeta().getOrDefault("projectId", threadId)) : threadId);
            finishPayload.put("silent", true);
            channelService.appendToThread(done.getUserId(), threadId, "system", "system",
                    "📨 " + done.getAssignee() + " 完成「" + done.getTitle() + "」status=" + done.getStatus()
                            + "\n" + trim(done.getResult(), 600),
                    finishPayload, "done");
        } catch (Exception e) {
            processRegistry.kill(taskId);
            synchronized (this) {
                get(taskId).ifPresent(t -> {
                    if ("cancelled".equalsIgnoreCase(t.getStatus())) return;
                    t.setStatus("failed");
                    t.setError(e.getMessage());
                    t.setFinishedAt(Instant.now().toString());
                    save();
                });
            }
        }
    }

    /**
     * Strict success: exit=0 AND RESULT_OK / RESULT.md evidence.
     * Budget deferrals and empty exit=0 runs are failures (not fake successes).
     */
    static String classifyResult(String result) {
        if (result == null || result.isBlank()) {
            return "failed";
        }
        String r = result;
        // The worker's transport header is authoritative. Document prose and tool
        // diagnostics may legitimately contain words such as “未找到” or “timed out”.
        if (r.startsWith("OpenCode exit=")) {
            String[] lines=r.split("\\R",3);
            return lines.length>=2 && "OpenCode exit=0".equals(lines[0]) && "RESULT_OK".equals(lines[1]) ? "succeeded" : "failed";
        }
        if (r.contains("API budget busy")
                || r.contains("task deferred")
                || r.contains("worker disabled")
                || r.contains("未安装")
                || r.contains("未启用")
                || r.contains("未找到")
                || r.contains("timed out")
                || r.contains("worker error")) {
            return "failed";
        }
        Matcher m = EXIT_CODE.matcher(r);
        Integer exit = null;
        if (m.find()) {
            try {
                exit = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        if (exit != null && exit != 0) {
            return "failed";
        }
        // Prefer explicit RESULT_OK marker from workers
        if (r.contains("RESULT_OK")) {
            return "succeeded";
        }
        // Structured JSON success
        if (r.contains("RESULT.json:") && r.contains("\"succeeded\"")) {
            return "succeeded";
        }
        // RESULT.md present in artifacts listing with exit 0
        if (exit != null && exit == 0 && r.contains("RESULT.md:")) {
            return "succeeded";
        }
        // OpenClaw HTTP success
        if (r.contains("OpenClaw HTTP 2") && r.contains("RESULT_OK")) {
            return "succeeded";
        }
        // exit=0 without evidence → false success
        if (exit != null && exit == 0) {
            return "failed";
        }
        return "failed";
    }

    /** Re-read a finished review's execution result without launching any work. */
    public synchronized AgentTask recheckReview(String userId,String taskId) {
        AgentTask task=get(taskId).orElseThrow(()->new IllegalArgumentException("执行任务不存在"));
        if(!userId.equals(task.getUserId())||!Boolean.TRUE.equals(task.getMeta().get("reviewOnly"))||List.of("running","queued","cancelled").contains(task.getStatus()))
            throw new IllegalArgumentException("只能复查本用户已结束的综述任务");
        boolean valid=OpenCodeWorker.hasSucceededResultJson(openCodeWorker.deliverDirFor(taskId).resolve("RESULT.json"));
        String status=valid?classifyResult(task.getResult()):"failed";
        if(!status.equals(task.getStatus())) {
            task.getMeta().put("previousExecutionStatus",task.getStatus());
            task.getMeta().put("executionRecheckedAt",Instant.now().toString());
            task.setStatus(status);task.setError("failed".equals(status)?task.getError():null);save();
        }
        return task;
    }

    private int countStatus(String s) {
        int n = 0;
        for (AgentTask t : tasks) {
            if (s.equalsIgnoreCase(t.getStatus())) n++;
        }
        return n;
    }

    /**
     * Aggregated live view for companion HUD (Chief + CODER + CLAW).
     */
    public Map<String, Object> live(String userId) {
        String uid = userId == null || userId.isBlank() ? defaultUser : userId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(status());

        Map<String, Object> chief = new LinkedHashMap<>();
        try {
            Map<String, Object> aut = autonomyService.status();
            chief.put("autonomy", Boolean.TRUE.equals(aut.get("enabled")));
            chief.put("tickRunning", Boolean.TRUE.equals(aut.get("tickRunning")));
            chief.put("intent", str(aut.get("lastSelfIntent")));
            chief.put("summary", trim(str(aut.get("lastSummary")), 400));
            chief.put("lastTickAt", str(aut.get("lastTickAt")));
            chief.put("totalTicks", aut.get("totalTicks"));
            boolean autoOn = Boolean.TRUE.equals(aut.get("enabled"));
            boolean tickRun = Boolean.TRUE.equals(aut.get("tickRunning"));
            long running = countStatus("running");
            long queued = countStatus("queued");
            if (tickRun) {
                chief.put("badge", "生活中·忙");
                chief.put("detail", str(aut.get("lastSelfIntent")));
            } else if (running + queued > 0) {
                chief.put("badge", "在调度");
                chief.put("detail", "小队有 " + (running + queued) + " 个任务");
            } else if (autoOn) {
                chief.put("badge", "生活中");
                chief.put("detail", trim(str(aut.get("lastSelfIntent")), 80));
            } else {
                chief.put("badge", "待命");
                chief.put("detail", "");
            }
        } catch (Exception e) {
            chief.put("badge", "待命");
            chief.put("error", e.getMessage());
        }
        out.put("chief", chief);

        out.put("coder", roleLive(uid, AgentRole.CODER));
        out.put("claw", roleLive(uid, AgentRole.CLAW));
        out.put("scribe", roleLive(uid, AgentRole.SCRIBE));
        return out;
    }

    private Map<String, Object> roleLive(String userId, AgentRole role) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<AgentTask> mine = list(userId, 40).stream()
                .filter(t -> t.getAssignee() == role)
                .collect(Collectors.toList());
        AgentTask active = mine.stream()
                .filter(t -> "running".equalsIgnoreCase(t.getStatus()) || "queued".equalsIgnoreCase(t.getStatus()))
                .findFirst()
                .orElse(null);
        AgentTask last = mine.isEmpty() ? null : mine.get(0);
        AgentTask focus = active != null ? active : last;
        if (focus == null) {
            m.put("badge", "待命");
            m.put("detail", "");
            return m;
        }
        m.put("taskId", focus.getId());
        m.put("title", focus.getTitle());
        m.put("brief", trim(focus.getBrief(), 240));
        m.put("status", focus.getStatus());
        m.put("startedAt", focus.getStartedAt());
        m.put("createdAt", focus.getCreatedAt());
        m.put("finishedAt", focus.getFinishedAt());
        m.put("elapsedSec", elapsedSec(focus.getStartedAt() != null ? focus.getStartedAt() : focus.getCreatedAt()));
        if ("running".equalsIgnoreCase(focus.getStatus())) {
            m.put("badge", "执行中");
            m.put("detail", focus.getTitle());
        } else if ("queued".equalsIgnoreCase(focus.getStatus())) {
            m.put("badge", "排队");
            m.put("detail", focus.getTitle());
        } else if ("succeeded".equalsIgnoreCase(focus.getStatus())) {
            m.put("badge", "刚完成");
            m.put("detail", focus.getTitle());
        } else if ("failed".equalsIgnoreCase(focus.getStatus()) || "cancelled".equalsIgnoreCase(focus.getStatus())) {
            m.put("badge", "失败");
            m.put("detail", focus.getTitle());
        } else {
            m.put("badge", focus.getStatus());
            m.put("detail", focus.getTitle());
        }
        if (focus.getMeta() != null) {
            m.put("meta", focus.getMeta());
            Object reportDir = focus.getMeta().get("reportDir");
            if (reportDir == null && (role == AgentRole.CODER || role == AgentRole.SCRIBE)) {
                reportDir = openCodeWorker.reportDirFor(focus.getId()).toAbsolutePath().toString();
            }
            if (reportDir != null) {
                m.put("stdoutTail", readTail(Path.of(String.valueOf(reportDir), "stdout.txt"), 2000));
                if (str(m.get("stdoutTail")).isBlank()) {
                    m.put("stdoutTail", readTail(Path.of(String.valueOf(reportDir), "bridge.md"), 2000));
                }
            }
        }
        if (focus.getError() != null) m.put("error", trim(focus.getError(), 300));
        return m;
    }

    private static long elapsedSec(String iso) {
        Instant s = parseInstant(iso);
        if (s == null) return 0;
        return Math.max(0, Instant.now().getEpochSecond() - s.getEpochSecond());
    }

    private static String readTail(Path file, int maxChars) {
        try {
            if (!Files.isRegularFile(file)) return "";
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.length() <= maxChars) return body;
            return body.substring(body.length() - maxChars);
        } catch (Exception e) {
            return "";
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static AgentRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) return AgentRole.CODER;
        String r = raw.trim().toUpperCase(Locale.ROOT);
        if (r.contains("CLAW") || r.contains("龙虾") || r.contains("OPENCLAW") || r.equals("CHANNEL")) {
            return AgentRole.CLAW;
        }
        if (r.contains("SCRIBE") || r.contains("排版") || r.contains("TYPESET")
                || r.contains("美化") || r.contains("写稿")) {
            return AgentRole.SCRIBE;
        }
        if (r.contains("CODE") || r.contains("CODER") || r.contains("OPENCODE") || r.contains("编程") || r.contains("SSH")
                || r.contains("训练")) {
            return AgentRole.CODER;
        }
        try {
            return AgentRole.valueOf(r);
        } catch (Exception e) {
            return AgentRole.CODER;
        }
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("agent_tasks.json");
    }

    private synchronized void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            List<AgentTask> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            tasks.clear();
            if (loaded != null) tasks.addAll(loaded);
            // re-queue interrupted running tasks
            for (AgentTask t : tasks) {
                if ("running".equalsIgnoreCase(t.getStatus())) {
                    if (Boolean.TRUE.equals(t.getMeta().get("reviewOnly"))) {
                        t.setStatus("failed");
                        t.setError("服务在综述执行中重启；须核对原进程与产物，未自动重复收费执行");
                    } else t.setStatus("queued");
                }
            }
        } catch (Exception e) {
            System.err.println("[MultiAgent] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tasks),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[MultiAgent] save failed: " + e.getMessage());
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
