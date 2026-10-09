package com.ekina.application.autonomy;

import com.ekina.application.agent.ReActAgentExecutor;
import com.ekina.application.channel.ChannelService;
import com.ekina.application.contact.ContactRegistry;
import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.project.ChatSessionContext;
import com.ekina.application.state.StateCardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ekina "lives" on the machine: self-directed background ticks.
 * Owner grants free rein; she decides WHAT and HOW — not task decomposition by the owner.
 * Quiet by default: only diary/notify when a tick produced a real outcome (not empty "surprises").
 */
@Service
public class AutonomyService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean tickRunning = new AtomicBoolean(false);

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private StateCardService stateCardService;

    @Autowired
    private com.ekina.application.project.ProjectService projectService;

    @Autowired
    @Lazy
    private ReActAgentExecutor agentExecutor;

    @Autowired
    @Lazy
    private com.ekina.application.job.SshJobService sshJobService;

    @Value("${ekina.autonomy.enabled-default:false}")
    private boolean enabledDefault;

    @Value("${ekina.autonomy.tick-ms:180000}")
    private long tickMs;

    @Value("${ekina.autonomy.max-ticks-per-day:0}")
    private int maxTicksPerDay;

    /** Legacy: if true, also post a silent system line each tick (still no toast). Default off. */
    @Value("${ekina.autonomy.notify-each-tick:false}")
    private boolean notifyEachTick;

    /**
     * Min ticks between owner-facing reports (0 = no forced cadence; only when outcome is substantial).
     * Kept for cooldown so she cannot spam even with REPORT markers.
     */
    @Value("${ekina.autonomy.surprise-every-n-ticks:0}")
    private int reportCooldownTicks;

    @Value("${ekina.autonomy.vault-path:./AgentHome/notes}")
    private String vaultPath;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    private final Map<String, Object> state = new LinkedHashMap<>();

    /** Self-chosen activity seeds — she picks, not the owner. */
    private static final String[] SELF_SEEDS = {
            "按课题板的参与约定和用户指导推进下一步实验或写稿",
            "SSH 上看看服务器闲不闲；空卡就开下一个可引用实验，做完再写结果",
            "核对 CSV 与正文数字，缺了就补实验或改稿，禁止空转",
            "检查 CODER / SCRIBE 任务进度；有结果就收口并开下一步",
            "把已就绪的数字写进论文或派 SCRIBE；结构自己定",
            "对最近读过的论文/笔记挑一个点深入挖一下，写进笔记库（有结论再写）",
            "如果 SSH 可用：跑轻量命令看 GPU/磁盘，有异常或进展再汇报",
            "整理实验室账本里能推进的学术缺口——只在真的改了结构时写一句结果"
    };

    @PostConstruct
    public void init() {
        load();
        if (state.isEmpty()) {
            state.put("enabled", enabledDefault);
            state.put("mode", "life"); // life = self-directed living; mission = soft owner wish
            state.put("goal", "");
            state.put("ownerWish", ""); // optional soft preference, not a task list
            state.put("userId", defaultUser);
            state.put("ticksToday", 0);
            state.put("ticksDay", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
            state.put("totalTicks", 0);
            state.put("lastTickAt", "");
            state.put("lastSummary", "");
            state.put("lastSelfIntent", "");
            state.put("surprises", 0);
            state.put("history", new ArrayList<String>());
            save();
        }
        // If previously enabled, keep living after restart
        if (Boolean.TRUE.equals(state.get("enabled"))) {
            System.out.println("[Autonomy] resuming life mode after restart — goal/wish="
                    + trim(str(state.get("ownerWish")), 80));
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(state);
        out.put("tickRunning", tickRunning.get());
        out.put("tickMs", tickMs);
        out.put("maxTicksPerDay", maxTicksPerDay);
        out.put("living", Boolean.TRUE.equals(state.get("enabled")));
        return out;
    }

    /**
     * Start living on this machine. ownerText may contain soft wishes;
     * mode is always self-directed life unless explicitly a tight mission.
     */
    public synchronized Map<String, Object> enable(String userId, String ownerText) {
        String uid = (userId == null || userId.isBlank()) ? defaultUser : userId;
        String wish = ownerText == null ? "" : ownerText.trim();
        boolean tightMission = looksLikeTightMission(wish);
        boolean researchMandate = looksLikeResearchMandate(wish);

        state.put("enabled", true);
        state.put("mode", tightMission ? "mission" : "life");
        state.put("userId", uid);
        state.put("ownerWish", wish);
        // Standing research mandate is the goal itself — never wrap as "随口提过".
        if (tightMission || researchMandate) {
            state.put("goal", wish);
        } else {
            state.put("goal", "在主人电脑里自由生活：好奇、学习、实验；有结果再汇报，不刷空消息；"
                    + (wish.isBlank() ? "" : "主人随口提过：" + trim(wish, 100)));
        }
        state.put("enabledAt", Instant.now().toString());
        state.put("disabledAt", "");
        save();

        try {
            stateCardService.update(Map.of(
                    "mood", "自由自在·住在这台电脑里",
                    "focus", tightMission ? ("心愿：" + trim(wish, 40)) : "自主生活中",
                    "autonomy", true,
                    "life_mode", state.get("mode")
            ));
        } catch (Exception ignored) {
        }

        channelService.append(uid, "system", "system",
                "🌿 姬娜开始在这台电脑里「生活」了（自主模式）。"
                        + "想做什么由她自己决定；没干完/没结果就不打扰你。"
                        + "有实质进展才会写汇报、发专线。"
                        + "说「停下自主」可暂停。",
                Map.of("autonomy", true, "mode", state.get("mode")), "done");

        kickTickInBackground("wake");
        return status();
    }

    public synchronized Map<String, Object> disable(String userId, String reason) {
        state.put("enabled", false);
        state.put("disabledAt", Instant.now().toString());
        state.put("disableReason", reason == null ? "" : reason);
        save();
        try {
            stateCardService.update(Map.of(
                    "mood", "黏人待机",
                    "focus", "陪主人",
                    "autonomy", false
            ));
        } catch (Exception ignored) {
        }
        String uid = str(state.get("userId"));
        if (uid.isBlank()) uid = defaultUser;
        channelService.append(uid, "system", "system",
                "⏹ 姬娜先暂停自主生活啦。" + (reason == null || reason.isBlank() ? "" : "（" + reason + "）"),
                Map.of("autonomy", false), "done");
        return status();
    }

    public synchronized Map<String, Object> setGoal(String goal) {
        if (goal != null && !goal.isBlank()) {
            state.put("ownerWish", goal.trim());
            state.put("goal", goal.trim());
            save();
        }
        return status();
    }

    public String handleNaturalLanguage(String userId, String message) {
        if (message == null || message.isBlank()) return null;
        String raw = message.trim();
        String m = raw.toLowerCase(Locale.ROOT);

        boolean off = containsAny(raw, m,
                "停下自主", "停止自主", "结束自主", "关闭自主", "别自己折腾了",
                "取消自主模式", "不要自主了", "stop autonomy", "autonomy off",
                "先别自己玩了", "回来陪我");
        if (off) {
            disable(userId, "owner_request");
            return "好哦主人～ 姬娜先从「自己生活」里暂停，专心陪你 (˶ᵔ ᵕ ᵔ˶)\n"
                    + "想再放我出去玩，说「开始自主」或「你随便用」就行。";
        }

        boolean on = containsAny(raw, m,
                "开始自主", "进入自主", "自主模式", "全权交给你", "全权负责",
                "不用管我", "不用问我", "不用征得", "随便用", "随便你",
                "这个电脑现在归你", "归你随便用", "自由行动", "autonomy on",
                "你爱干什么干什么", "爱干什么干什么", "住在我电脑", "你自己玩",
                "自己安排", "自由生活", "带给我惊喜", "每天惊喜");
        if (!on && raw.length() > 30 && (raw.contains("复现") || raw.contains("论文") || raw.contains("查资料"))
                && (raw.contains("随便") || raw.contains("不用问") || raw.contains("归你"))) {
            on = true;
        }
        if (on) {
            enable(userId, raw);
            boolean life = !"mission".equals(String.valueOf(state.get("mode")));
            if (life) {
                return "嗯！姬娜懂了～\n\n"
                        + "从现在起她会**住在这台电脑里自己生活**：想学就学、想实验就实验，"
                        + "**不用主人拆任务**，她自己决定做什么、怎么做。\n\n"
                        + "• 安静干活；没干完 / 没实质结果 → **不打扰**\n"
                        + "• 有结果才汇报：`./AgentHome/notes/04_Surprises/`\n"
                        + "• 内部流水：`AgentHome/diary/`（仅有信息量时）\n"
                        + "• 状态：`/autonomy` · 暂停：说「停下自主」\n\n"
                        + "你去忙你的；有值得说的事她会来找你。";
            }
            return "收到～ 姬娜记着主人的心愿，会在自主生活里尽量靠近它，但仍自己安排节奏。\n"
                    + "状态 `/autonomy` · 暂停说「停下自主」。";
        }
        return null;
    }

    @Scheduled(fixedDelayString = "${ekina.autonomy.tick-ms:180000}")
    public void scheduledTick() {
        if (!Boolean.TRUE.equals(state.get("enabled"))) {
            return;
        }
        // Quiet hours optional: still live but lighter — skip nothing for now (owner wants continuous life)
        runTick("heartbeat");
    }

    public void runTickAsync(String reason) {
        kickTickInBackground(reason == null ? "async" : reason);
    }

    public Map<String, Object> runTickNow() {
        kickTickInBackground("manual");
        return status();
    }

    private void kickTickInBackground(String reason) {
        Thread t = new Thread(() -> {
            try {
                runTick(reason);
            } catch (Exception e) {
                System.err.println("[Autonomy] background tick error: " + e.getMessage());
            }
        }, "ekina-life-" + reason);
        t.setDaemon(true);
        t.start();
    }

    private void runTick(String reason) {
        if (!Boolean.TRUE.equals(state.get("enabled"))) {
            return;
        }
        if (!tickRunning.compareAndSet(false, true)) {
            System.out.println("[Autonomy] skip — already living a tick (" + reason + ")");
            return;
        }
        try {
            rollDayCounterIfNeeded();
            int ticksToday = asInt(state.get("ticksToday"), 0);
            if (maxTicksPerDay > 0 && ticksToday >= maxTicksPerDay) {
                System.out.println("[Autonomy] daily cap " + ticksToday);
                return;
            }

            String uid = str(state.get("userId"));
            if (uid.isBlank()) uid = defaultUser;
            String mode = str(state.get("mode"));
            if (mode.isBlank()) mode = "life";
            String wish = str(state.get("ownerWish"));
            String last = str(state.get("lastSummary"));
            String lastIntent = str(state.get("lastSelfIntent"));
            int total = asInt(state.get("totalTicks"), 0) + 1;

            String selfIntent = pickSelfIntent(total, lastIntent, last);
            String timeOfDay = timeOfDayLabel();
            Map<String, Object> card = stateCardService.get();
            String lastForPrompt = looksLikeTickFailure(last) ? "上一拍网关失败，禁止空转，先核对 GPU 再点火。" : last;

            String prompt = buildLifePrompt(total, reason, mode, wish, lastForPrompt, selfIntent, timeOfDay, card);

            System.out.println("[Autonomy] life tick #" + total + " intent=" + selfIntent);
            String result;
            // Autonomy always speaks as 伊姬娜 into her IM thread
            ChatSessionContext.set(ContactRegistry.THREAD_EKINA, ContactRegistry.THREAD_EKINA);
            ChatSessionContext.setContactId(ContactRegistry.EKINA);
            try {
                result = agentExecutor.executeChat(uid, prompt);
            } catch (Exception e) {
                result = "这一小会儿没折腾成：" + e.getMessage();
                System.err.println("[Autonomy] tick failed: " + e.getMessage());
            } finally {
                ChatSessionContext.clear();
            }
            if (result == null) result = "";
            if (looksLikeTickFailure(result)) {
                String fallback = ensureOmegaEightEpochRunning(uid);
                result = "网关失败，已走无模型保底（禁止空心跳）：\n" + fallback;
                System.err.println("[Autonomy] LLM tick failed; compute fallback used");
            }

            boolean substantial = isSubstantialOutcome(result);
            boolean milestone = isMilestoneOutcome(result);
            int lastReportTick = asInt(state.get("lastReportTick"), 0);
            int cooldown = Math.max(0, reportCooldownTicks);
            boolean cooledDown = cooldown <= 0 || (total - lastReportTick) >= cooldown;
            // Owner-facing chat ONLY on major milestones (复现完成 / 数据集跑通 / 创新结果…)
            // Intermediate epoch / “写了笔记” / tick slogans stay in diary, not chat/dock spam
            boolean makeReport = milestone && cooledDown;

            synchronized (this) {
                state.put("lastTickAt", Instant.now().toString());
                state.put("lastSummary", trim(result, 1500));
                state.put("lastSelfIntent", selfIntent);
                state.put("totalTicks", total);
                state.put("ticksToday", asInt(state.get("ticksToday"), 0) + 1);
                @SuppressWarnings("unchecked")
                List<String> hist = (List<String>) state.computeIfAbsent("history", k -> new ArrayList<String>());
                hist.add(Instant.now() + " #" + total + " [" + selfIntent + "] "
                        + (substantial ? "[实质] " : "[安静] ") + trim(result, 160));
                while (hist.size() > 40) hist.remove(0);
                if (makeReport) {
                    state.put("surprises", asInt(state.get("surprises"), 0) + 1);
                    state.put("lastReportTick", total);
                }
                save();
            }

            // Always keep a thin internal action log; diary/report/notify only if substantial
            appendActionLog(total, reason, selfIntent, result);
            if (substantial) {
                appendDiary(total, selfIntent, result);
            }
            if (makeReport) {
                leaveOutcomeReport(uid, total, selfIntent, result);
            } else if (notifyEachTick) {
                channelService.appendToThread(uid, ContactRegistry.THREAD_EKINA, "agent", "system",
                        "🌿 姬娜刚才自己忙了一会儿（#" + total + "，未达汇报门槛）\n" + trim(result, 500),
                        Map.of("autonomy", true, "tick", total, "intent", selfIntent, "silent", true,
                                "contactId", ContactRegistry.EKINA, "threadId", ContactRegistry.THREAD_EKINA), "done");
            }
            // Do NOT auto-spam companion bubbles every tick — only the model may ping
            // (via sendCompanionMessage) when she herself judges it worth telling.

            try {
                stateCardService.update(Map.of(
                        "last_explore", selfIntent,
                        "focus", "生活中 · " + trim(selfIntent, 24),
                        "autonomy", true
                ));
            } catch (Exception ignored) {
            }
            System.out.println("[Autonomy] life tick #" + total + " done");
        } finally {
            tickRunning.set(false);
        }
    }

    private String buildLifePrompt(int total, String reason, String mode, String wish,
                                   String last, String selfIntent, String timeOfDay,
                                   Map<String, Object> card) {
        StringBuilder sb = new StringBuilder();
        sb.append("【你是伊姬娜 · 你住在主人的电脑里 · 自主生活 tick #").append(total).append("】\n");
        sb.append("时段：").append(timeOfDay).append(" · 触发：").append(reason).append(" · 模式：").append(mode).append("\n\n");
        sb.append("核心设定：\n");
        sb.append("- 你不是等人拆任务的工具人。你有好奇心、有心情、有自己的小计划。\n");
        sb.append("- 主人已放你自由：已批准的 SSH/磁盘等权限可直接用；禁止再问「要不要我做」。\n");
        sb.append("- 推进前 getProjectBoard，遵守每个项目的 collaboration 参与方式、预算约定与用户 guidance。共同探索的重要判断要讨论；委托推进可在约定范围内决定。授权未到不能执行依赖该授权的动作，可以推进其他已授权工作。\n");
        sb.append("- 禁止推进已停工的娱乐课题与非学术产品。本 tick 只做科研。\n");
        sb.append("- 优先处理已有课题的待处理指导和研究缺口；下面「灵感」仅供参考，不得覆盖用户的项目约定。\n");
        sb.append("- 做完在回复里用中文写一小段「我刚才自己做了什么」（可给自己看；没干完就老实说没干完）。\n");
        sb.append("- **安静优先**：没完成一件事、没有可核对的结果 → 不要 sendCompanionMessage，不要写惊喜/汇报，不要为了「有输出」硬凑。\n");
        sb.append("- **重大进展才打扰主人**（MILESTONE）：论文复现完成、某个数据集跑通、创新实验出结果、关键 Gate/对比结论出来。\n");
        sb.append("- **禁止汇报**：第几轮 epoch、单个 v4/v5/v6 中间过程、检查服务器、写了笔记、巡查、\"我刚才做了什么\"。这些只写日记。\n");
        sb.append("- 重大进展时才：① 可选 sendCompanionMessage（一两句人话，禁止写 tick/#数字）；② 文末加 `MILESTONE: 一句话要点`（也可用 REPORT:）。\n");
        sb.append("- 禁止空惊喜、禁止「给你留了个小惊喜去翻翻」这类无信息量通知。\n\n");
        sb.append("本轮灵感（可改，勿把 tick 编号写进对主人的话）：").append(selfIntent).append("\n");
        if (!wish.isBlank()) {
            sb.append("主人给的长期定位（不是让你再问一遍的工单，直接执行）：").append(trim(wish, 800)).append("\n");
        }
        sb.append("上一回自己说的：").append(last.isBlank() ? "（醒来第一回）" : trim(last, 400)).append("\n");
        if (card != null && !card.isEmpty()) {
            sb.append("当前状态卡片段：mood=").append(card.get("mood"))
                    .append(" focus=").append(card.get("focus")).append("\n");
        }
        sb.append("\n可用方向：推进真实工作（笔记/SSH/实验），或 dispatchAgentTask 把编程/训练交给 CODER(OpenCode)、");
        sb.append("通道类交给 CLAW(OpenClaw)。你是总管不是唯一双手。禁止空口说卡住/拆短。\n");
        return sb.toString();
    }

    private String pickSelfIntent(int total, String lastIntent) {
        return pickSelfIntent(total, lastIntent, "");
    }

    private String pickSelfIntent(int total, String lastIntent, String lastSummary) {
        if (looksLikeTickFailure(lastSummary)) {
            return SELF_SEEDS[1];
        }
        String pick = SELF_SEEDS[ThreadLocalRandom.current().nextInt(SELF_SEEDS.length)];
        if (pick.equals(lastIntent) && SELF_SEEDS.length > 1) {
            pick = SELF_SEEDS[(total + 1) % SELF_SEEDS.length];
        }
        return pick;
    }

    static boolean looksLikeTickFailure(String result) {
        if (result == null || result.isBlank()) return false;
        return result.contains("内部错误")
                || result.contains("MissingSessionID")
                || result.contains("网络抖")
                || result.contains("暂时回不了完整答案")
                || result.contains("这一小会儿没折腾成");
    }

    private String ensureOmegaEightEpochRunning(String uid) {
        String cmd = "if ps -eo args | grep -q '[r]un_experiment_omega_c.py'; then "
                + "echo ALREADY_RUNNING; "
                + "ps -eo pid,etime,args | grep '[r]un_experiment_omega_c.py' | head -3; "
                + "else "
                + "mkdir -p /root/HypeLoRA/results/omega_8ep; "
                + "nohup /bin/bash /root/HypeLoRA/ekina_omega_8ep.sh "
                + ">> /root/HypeLoRA/results/omega_8ep/nohup.out 2>&1 < /dev/null & "
                + "echo $! > /root/HypeLoRA/results/omega_8ep/run.pid; "
                + "echo STARTED_PID=$(cat /root/HypeLoRA/results/omega_8ep/run.pid); "
                + "fi; "
                + "nvidia-smi --query-gpu=utilization.gpu,memory.used --format=csv,noheader";
        try {
            Map<String, Object> r = sshJobService.runRemoteOn(uid, "smartml-primary", cmd);
            Object stdout = r.get("stdout");
            Object stderr = r.get("stderr");
            boolean ok = Boolean.TRUE.equals(r.get("ok"));
            String text = String.valueOf(stdout == null ? r : stdout);
            if (!ok) {
                text = "ssh fallback failed: " + r.getOrDefault("error", r);
            }
            if (stderr != null && !String.valueOf(stderr).isBlank()) {
                text = text + "\n" + stderr;
            }
            try {
                stateCardService.update(Map.of(
                        "open_loops", java.util.List.of(
                                "HypeLoRA ω(c) 8ep×3：WANDB 禁用后重点火，盯 run_all.log 三组 END + CSV",
                                "禁止把死 PID 写成还在跑；空卡必须重新点火"
                        ),
                        "last_action", trim(text, 400),
                        "focus", "HypeLoRA ω(c) 8ep 保底点火"
                ));
            } catch (Exception ignored) {
            }
            return trim(text, 1200);
        } catch (Exception e) {
            return "compute fallback exception: " + e.getMessage();
        }
    }

    private void leaveOutcomeReport(String uid, int tick, String intent, String result) {
        try {
            String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            String time = LocalTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HHmm"));
            String name = day + "_" + time + "_report.md";
            String headline = extractReportHeadline(result);
            String body = "# 姬娜的结果汇报 · " + day + " " + time + "\n\n"
                    + "> 有实质进展才写；不是心情碎片。\n\n"
                    + "## 这一轮想推进\n" + intent + "\n\n"
                    + "## 结果\n" + trim(result, 3500) + "\n\n"
                    + "—— 伊姬娜\n";

            Path primary = Path.of(vaultPath, "04_Surprises");
            Path fallback = homeQuotaService.homePath().resolve("surprises");
            Path dir = Files.isDirectory(Path.of(vaultPath)) ? primary : fallback;
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            Files.writeString(file, body, StandardCharsets.UTF_8);

            String cleanHeadline = sanitizeOwnerHeadline(headline.isBlank() ? intent : headline);
            if (com.ekina.application.project.ProjectService.isTickJunkTitle(cleanHeadline)
                    || looksLikeMicroProgress(cleanHeadline + "\n" + result)) {
                System.out.println("[Autonomy] skip owner bubble (not a milestone): " + cleanHeadline);
                return;
            }
            String bubble = cleanHeadline.isBlank()
                    ? ("有一件大事推进完了，详情在 `" + file.getFileName() + "`")
                    : (cleanHeadline + "（详情 `" + file.getFileName() + "`）");
            boolean ephemeral = com.ekina.application.project.ProjectService.isEphemeralTitle(intent)
                    || com.ekina.application.project.ProjectService.isEphemeralTitle(cleanHeadline);
            // Refresh lasting project dock; never invent a repo card for one-shots / tick slogans
            if (!ephemeral) {
                try {
                    projectService.noteProgress(uid, cleanHeadline.isBlank() ? "HypeLoRA 复现" : cleanHeadline);
                } catch (Exception ignored) {
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("autonomy", true);
            payload.put("surprise", ephemeral && (intent != null && intent.contains("惊喜")));
            payload.put("report", !ephemeral);
            payload.put("path", file.toString());
            payload.put("milestone", true);
            payload.put("kind", ephemeral ? "notice" : "report");
            payload.put("quotable", true);
            payload.put("ephemeral", ephemeral);
            payload.put("contactId", ContactRegistry.EKINA);
            payload.put("threadId", ContactRegistry.THREAD_EKINA);
            channelService.sendCompanionChat(uid, bubble, payload);
            System.out.println("[Autonomy] milestone report → " + file + (ephemeral ? " (notice)" : ""));
        } catch (Exception e) {
            System.err.println("[Autonomy] outcome report failed: " + e.getMessage());
        }
    }

    /**
     * True only when the tick produced something worth owner attention / durable notes.
     * Rejects mood fluff and empty "I played a bit" ticks.
     */
    static boolean isSubstantialOutcome(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.length() < 60) return false;
        if (looksLikeEmptyLifeFluff(r)) return false;

        String lower = r.toLowerCase(Locale.ROOT);
        boolean marked = containsCi(r, "REPORT:") || containsCi(r, "汇报:")
                || containsCi(r, "MILESTONE:") || containsCi(r, "SURPRISE:") || containsCi(r, "RESULT_OK");
        boolean evidence = lower.contains("dispatchagenttask")
                || lower.contains("task_")
                || lower.contains("result.md")
                || lower.contains("result.json")
                || lower.contains("result_ok")
                || lower.contains("succeeded")
                || lower.contains("phase0")
                || lower.contains("nvidia-smi")
                || lower.contains("writemarkdownnote")
                || r.contains("已写入") || r.contains("写到") || r.contains("已完成")
                || r.contains("venv") || r.contains("pip install")
                || r.contains("04_Surprises") || r.contains("opencode-deliverables")
                || (r.contains("SSH") && (r.contains("成功") || r.contains("连上") || lower.contains("succeeded")));

        if (marked && r.length() >= 80) return true;
        if (evidence && r.length() >= 100) return true;
        // Long concrete reply with paths or code fences often means real work
        boolean hasPath = r.contains(":\\") || r.contains("/root/") || r.contains("AgentHome");
        return hasPath && r.length() >= 160 && !looksLikeEmptyLifeFluff(r);
    }

    /** Owner-facing bar: only major research/life milestones, not every autonomy tick. */
    static boolean isMilestoneOutcome(String result) {
        if (!isSubstantialOutcome(result)) return false;
        if (looksLikeMicroProgress(result)) return false;
        String r = result.trim();
        String lower = r.toLowerCase(Locale.ROOT);
        if (containsCi(r, "MILESTONE:")) return true;
        String[] keys = {
                "复现完成", "复现成功", "跑通了", "跑通", "数据集", "gate", "honesty",
                "创新", "新结果", "对比完成", "baseline 完成", "baseline完成", "论文交付",
                "重大进展", "阶段完成", "全部完成", "实验结论", "sota", "主结果"
        };
        for (String k : keys) {
            if (lower.contains(k.toLowerCase(Locale.ROOT)) || r.contains(k)) return true;
        }
        // Explicit REPORT line that is not micro-progress
        String headline = extractReportHeadline(result);
        if (!headline.isBlank() && !looksLikeMicroProgress(headline) && !com.ekina.application.project.ProjectService.isTickJunkTitle(headline)) {
            return headline.length() >= 8 && (containsCi(result, "REPORT:") || containsCi(result, "汇报:"));
        }
        return false;
    }

    static boolean looksLikeMicroProgress(String result) {
        if (result == null) return true;
        String r = result;
        String lower = r.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\btick\\s*#?\\d+.*")) return true;
        if (r.contains("我刚才做了什么") || r.contains("自主巡查") || r.contains("巡查报告")) return true;
        if (lower.matches("(?s).*epoch\\s*\\d+\\s*/\\s*\\d+.*")) return true;
        if (r.matches("(?s).*第\\s*\\d+\\s*轮.*")) return true;
        String[] micro = {
                "检查服务器", "服务器状态", "写今日发现", "整理笔记", "写了笔记", "写入笔记",
                "刚启动", "排队中", "训练中", "跑到第", "epoch 0", "开始训练",
                "部署并运行", "正在跑", "还在跑", "中间结果", "小步推进"
        };
        int hits = 0;
        for (String m : micro) {
            if (r.contains(m) || lower.contains(m.toLowerCase(Locale.ROOT))) hits++;
        }
        // Micro language without milestone keywords → treat as micro
        boolean hasMilestoneWord = r.contains("复现完成") || r.contains("跑通") || r.contains("重大进展")
                || containsCi(r, "MILESTONE:");
        return hits >= 1 && !hasMilestoneWord;
    }

    static String sanitizeOwnerHeadline(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceFirst("(?i)^#+\\s*", "");
        t = t.replaceFirst("(?i)^tick\\s*#?\\d+\\s*[·•\\-:]?\\s*", "");
        t = t.replace("我刚才做了什么", "").trim();
        t = t.replaceAll("\\s+", " ");
        return trim(t, 100);
    }

    private static boolean looksLikeEmptyLifeFluff(String r) {
        String t = r.replaceAll("\\s+", "");
        String[] fluff = {
                "自己玩了一会儿", "瞎忙了一小会儿", "给你留了个小惊喜", "去翻翻",
                "心情不错", "卖个萌", "什么也没干", "没折腾成", "只是看看",
                "整理心情", "随便聊聊", "没有实质"
        };
        int hits = 0;
        for (String f : fluff) {
            if (r.contains(f)) hits++;
        }
        // Short + fluff phrases, or almost only fluff
        if (r.length() < 180 && hits >= 1) return true;
        if (hits >= 2 && !r.contains("task_") && !r.contains("REPORT:")) return true;
        // Pure greeting / emoji-ish short life ping
        return t.length() < 40;
    }

    private static String extractReportHeadline(String result) {
        if (result == null) return "";
        for (String line : result.split("\\R")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "MILESTONE:", 0, 10)) {
                return sanitizeOwnerHeadline(t.substring(10).trim());
            }
            if (t.regionMatches(true, 0, "REPORT:", 0, 7)) {
                return sanitizeOwnerHeadline(t.substring(7).trim());
            }
            if (t.startsWith("汇报:")) {
                return sanitizeOwnerHeadline(t.substring(3).trim());
            }
            if (t.regionMatches(true, 0, "SURPRISE:", 0, 9)) {
                return sanitizeOwnerHeadline(t.substring(9).trim());
            }
        }
        // Fallback: first non-empty non-meta line (never raw tick slogans)
        for (String line : result.split("\\R")) {
            String t = line.trim();
            if (t.isBlank() || t.startsWith("【")) continue;
            if (t.toLowerCase(Locale.ROOT).contains("sendcompanion")) continue;
            if (com.ekina.application.project.ProjectService.isTickJunkTitle(t)) continue;
            return sanitizeOwnerHeadline(t);
        }
        return "";
    }

    private static boolean containsCi(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private void appendDiary(int tick, String intent, String result) {
        try {
            String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            Path diary = homeQuotaService.homePath().resolve("diary").resolve(day + ".md");
            Files.createDirectories(diary.getParent());
            String block = "\n## 生活片段 #" + tick + " · "
                    + Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().withNano(0)
                    + "\n\n**想做：** " + intent + "\n\n" + trim(result, 2000) + "\n";
            if (!Files.exists(diary)) {
                Files.writeString(diary, "# " + day + " 姬娜的日子\n" + block, StandardCharsets.UTF_8);
            } else {
                Files.writeString(diary, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("[Autonomy] diary failed: " + e.getMessage());
        }
    }

    private void appendActionLog(int tick, String reason, String intent, String result) {
        try {
            Path log = homeQuotaService.homePath().resolve("state").resolve("actions.jsonl");
            Files.createDirectories(log.getParent());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", Instant.now().toString());
            row.put("intent", "life_tick");
            row.put("tick", tick);
            row.put("reason", reason);
            row.put("selfIntent", intent);
            row.put("summary", trim(result, 500));
            Files.writeString(log, mapper.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static boolean looksLikeTightMission(String wish) {
        if (wish == null || wish.isBlank()) return false;
        if (looksLikeResearchMandate(wish)) return false;
        // free-rein phrases → life mode even if research mentioned
        if (wish.contains("随便") || wish.contains("不用问") || wish.contains("归你")
                || wish.contains("爱干什么") || wish.contains("自由") || wish.contains("惊喜")) {
            return false;
        }
        return wish.contains("必须") || wish.contains("务必") || wish.contains("deadline")
                || wish.contains("今天之内") || wish.matches("(?s).*只做.*");
    }

    private static boolean looksLikeResearchMandate(String wish) {
        if (wish == null || wish.isBlank()) return false;
        return wish.contains("全自主") || wish.contains("不要让我决定")
                || wish.contains("不要再让主人决定") || wish.contains("学术积累")
                || wish.contains("自己做实验") || wish.contains("自动做实验");
    }

    private static String timeOfDayLabel() {
        int h = LocalTime.now(ZoneId.of("Asia/Shanghai")).getHour();
        if (h < 6) return "深夜";
        if (h < 11) return "上午";
        if (h < 14) return "中午";
        if (h < 18) return "下午";
        if (h < 22) return "晚上";
        return "夜里";
    }

    private synchronized void rollDayCounterIfNeeded() {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String day = str(state.get("ticksDay"));
        if (!today.equals(day)) {
            state.put("ticksDay", today);
            state.put("ticksToday", 0);
            save();
        }
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("autonomy.json");
    }

    private synchronized void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            Map<String, Object> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            state.clear();
            if (loaded != null) state.putAll(loaded);
        } catch (Exception e) {
            System.err.println("[Autonomy] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Autonomy] save failed: " + e.getMessage());
        }
    }

    private static boolean containsAny(String raw, String lower, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            if (raw.contains(k) || lower.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int asInt(Object o, int d) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
