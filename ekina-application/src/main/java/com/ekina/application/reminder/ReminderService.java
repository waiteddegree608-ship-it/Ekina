package com.ekina.application.reminder;

import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.channel.ChannelService;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Deterministic reminder scheduler.
 * - Fires only when nextFireAt is due
 * - At most ONE pending card per ruleId (old ones are superseded)
 * - Natural language like "我吃过药了" auto-dismisses matching pending cards
 */
@Service
public class ReminderService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ReminderRule> rules = new CopyOnWriteArrayList<>();

    @Autowired
    private ChannelService channelService;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.reminders.enabled:true}")
    private boolean enabled;

    private Path rulesFile() {
        return Path.of(sandboxDir, "reminders", "rules.json");
    }

    @PostConstruct
    public void init() {
        load();
        if (rules.isEmpty()) {
            seedDefaults(defaultUser);
            save();
        }
        // One-time cleanup of stacked pending reminder cards
        try {
            dedupePendingCards(defaultUser);
        } catch (Exception e) {
            System.err.println("[ReminderService] dedupe on init: " + e.getMessage());
        }
    }

    public synchronized List<ReminderRule> listRules(String userId) {
        if (userId == null || userId.isBlank()) {
            return new ArrayList<>(rules);
        }
        List<ReminderRule> out = new ArrayList<>();
        for (ReminderRule r : rules) {
            if (userId.equals(r.getUserId())) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized ReminderRule upsert(ReminderRule rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId("rem_" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (rule.getUserId() == null || rule.getUserId().isBlank()) {
            rule.setUserId(defaultUser);
        }
        if (rule.getNextFireAt() == null) {
            rule.setNextFireAt(computeNextFire(rule, ZonedDateTime.now(ZONE)).toInstant().toString());
        }
        boolean replaced = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getId().equals(rule.getId())) {
                rules.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rules.add(rule);
        }
        save();
        return rule;
    }

    public synchronized boolean delete(String id) {
        boolean removed = rules.removeIf(r -> r.getId().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Ack / snooze a reminder card and reschedule the underlying rule.
     * <p>
     * Product rule: 「已做 / 确认」means the owner handled it for today —
     * interval rules (standup etc.) must NOT fire again ~45 minutes later.
     */
    public synchronized ChannelMessage ack(String userId, String messageId, String action) {
        Map<String, Object> patch = new HashMap<>();
        String act = action == null || action.isBlank() ? "ack" : action;
        patch.put("action", act);
        patch.put("actedAt", Instant.now().toString());
        String status = "acked";

        // Pending cards may live outside the current ChatSessionContext thread window.
        ChannelMessage existing = channelService.findById(userId, messageId);
        if (existing == null) {
            throw new IllegalArgumentException("reminder card not found: " + messageId);
        }
        String ruleId = null;
        if (existing.getPayload() != null && existing.getPayload().get("ruleId") != null) {
            ruleId = String.valueOf(existing.getPayload().get("ruleId"));
        }

        ZonedDateTime now = ZonedDateTime.now(ZONE);
        if (act.startsWith("snooze")) {
            status = "snoozed";
            int minutes = 10;
            if (act.contains("1h") || act.contains("60m")) {
                minutes = 60;
            } else if (act.contains("30m")) {
                minutes = 30;
            } else if (act.matches("(?i).*snooze_(\\d+)m.*")) {
                try {
                    minutes = Integer.parseInt(act.replaceAll("(?i).*snooze_(\\d+)m.*", "$1"));
                } catch (Exception ignored) {
                    minutes = 10;
                }
            }
            if (ruleId != null) {
                for (ReminderRule r : rules) {
                    if (ruleId.equals(r.getId())) {
                        r.setNextFireAt(now.plusMinutes(minutes).toInstant().toString());
                        break;
                    }
                }
                save();
            }
        } else {
            // ack: interval / mute_today → silent until tomorrow 09:00; daily → next real slot
            if (ruleId != null) {
                for (ReminderRule r : rules) {
                    if (!ruleId.equals(r.getId())) {
                        continue;
                    }
                    boolean interval = "interval".equalsIgnoreCase(r.getKind());
                    if (interval || "mute_today".equalsIgnoreCase(act)) {
                        ZonedDateTime nextMorning = now.toLocalDate()
                                .plusDays(1)
                                .atTime(LocalTime.of(9, 0))
                                .atZone(ZONE);
                        r.setNextFireAt(nextMorning.toInstant().toString());
                    } else {
                        r.setNextFireAt(computeNextFire(r, now.plusSeconds(60)).toInstant().toString());
                    }
                    break;
                }
                save();
            }
        }

        ChannelMessage updated = channelService.updateStatus(userId, messageId, status, patch);
        if (updated == null) {
            throw new IllegalStateException("failed to update reminder status: " + messageId);
        }
        // Also clear any other pending cards of same rule (including the acked id if still pending)
        if (ruleId != null) {
            supersedePendingForRule(userId, ruleId, null);
        }
        return updated;
    }

    /**
     * Natural language: "我吃过药了" / "起来了" / "喝过水了" → dismiss matching pending cards.
     * Returns how many cards were closed.
     */
    public int handleNaturalLanguageCompletion(String userId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return 0;
        }
        String uid = userId == null ? defaultUser : userId;
        String msg = userMessage.toLowerCase(Locale.ROOT);

        Set<String> ruleIds = new HashSet<>();
        // medicine
        if (containsAny(msg, "吃了药", "吃过药", "已经吃药", "药吃了", "服过药", "吃药了", "药已经吃", "已经服药")) {
            ruleIds.add("rem_medicine");
            ruleIds.addAll(findRuleIdsByTitle(uid, "药"));
        }
        // standup / stretch
        if (containsAny(msg, "起来了", "起来活动", "站起来了", "走动了", "活动过了", "已经起来", "刚起来走走", "久坐好了")) {
            ruleIds.add("rem_standup");
            ruleIds.addAll(findRuleIdsByTitle(uid, "起来", "走走", "活动", "久坐"));
        }
        // water
        if (containsAny(msg, "喝了水", "喝过水", "已经喝水", "水喝了", "刚喝水")) {
            ruleIds.addAll(findRuleIdsByTitle(uid, "水", "喝水"));
        }

        if (ruleIds.isEmpty()) {
            // generic "提醒关掉/不用再提醒这个"
            if (containsAny(msg, "不用再提醒", "别再提醒", "关掉提醒", "关闭提醒", "取消提醒", "card里还", "卡片还")) {
                // if talking about medicine specifically already handled; else clear all pending reminders mentioned
                if (msg.contains("药")) {
                    ruleIds.add("rem_medicine");
                    ruleIds.addAll(findRuleIdsByTitle(uid, "药"));
                }
            }
        }

        int closed = 0;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        for (String ruleId : ruleIds) {
            closed += dismissPendingForRule(uid, ruleId, "acked_by_chat");
            for (ReminderRule r : rules) {
                if (ruleId.equals(r.getId()) && uid.equals(r.getUserId())) {
                    if ("interval".equalsIgnoreCase(r.getKind())) {
                        int mins = r.getEveryMinutes() == null ? 45 : r.getEveryMinutes();
                        r.setNextFireAt(now.plusMinutes(mins).toInstant().toString());
                    } else {
                        r.setNextFireAt(computeNextFire(r, now.plusSeconds(60)).toInstant().toString());
                    }
                }
            }
        }
        if (closed > 0) {
            save();
            System.out.println("[ReminderService] NL completion closed " + closed + " cards for " + ruleIds);
        }
        return closed;
    }

    public int dismissPendingForRule(String userId, String ruleId, String reason) {
        int n = 0;
        // Must scan ALL pending (not UI-deduped list) or older stacked cards survive forever.
        for (ChannelMessage m : channelService.listAllPending(userId)) {
            if (!"reminder".equals(m.getType())) continue;
            if (m.getPayload() == null) continue;
            Object rid = m.getPayload().get("ruleId");
            if (rid != null && ruleId.equals(String.valueOf(rid))) {
                Map<String, Object> patch = new HashMap<>();
                patch.put("action", reason);
                patch.put("actedAt", Instant.now().toString());
                channelService.updateStatus(userId, m.getId(), "acked", patch);
                n++;
            }
        }
        return n;
    }

    /** Keep only the newest pending reminder card per ruleId; supersede the rest. */
    public void dedupePendingCards(String userId) {
        Map<String, ChannelMessage> latest = new LinkedHashMap<>();
        // newest-first from listAllPending
        for (ChannelMessage m : channelService.listAllPending(userId)) {
            if (!"reminder".equals(m.getType())) continue;
            String ruleId = "?";
            if (m.getPayload() != null && m.getPayload().get("ruleId") != null) {
                ruleId = String.valueOf(m.getPayload().get("ruleId"));
            }
            if (!latest.containsKey(ruleId)) {
                latest.put(ruleId, m);
            } else {
                Map<String, Object> patch = new HashMap<>();
                patch.put("action", "superseded");
                patch.put("actedAt", Instant.now().toString());
                channelService.updateStatus(userId, m.getId(), "superseded", patch);
            }
        }
    }

    private void supersedePendingForRule(String userId, String ruleId, String keepId) {
        for (ChannelMessage m : channelService.listAllPending(userId)) {
            if (!"reminder".equals(m.getType())) continue;
            if (keepId != null && keepId.equals(m.getId())) continue;
            if (m.getPayload() != null && ruleId.equals(String.valueOf(m.getPayload().get("ruleId")))) {
                Map<String, Object> patch = new HashMap<>();
                patch.put("action", "superseded");
                channelService.updateStatus(userId, m.getId(), "superseded", patch);
            }
        }
    }

    private boolean hasPendingForRule(String userId, String ruleId) {
        if (ruleId == null) return false;
        for (ChannelMessage m : channelService.listAllPending(userId)) {
            if (!"reminder".equals(m.getType()) || m.getPayload() == null) continue;
            if (ruleId.equals(String.valueOf(m.getPayload().get("ruleId")))) return true;
        }
        return false;
    }

    private List<String> findRuleIdsByTitle(String userId, String... keywords) {
        List<String> ids = new ArrayList<>();
        for (ReminderRule r : rules) {
            if (!userId.equals(r.getUserId())) continue;
            String t = (r.getTitle() == null ? "" : r.getTitle()) + (r.getBody() == null ? "" : r.getBody());
            for (String k : keywords) {
                if (t.contains(k)) {
                    ids.add(r.getId());
                    break;
                }
            }
        }
        return ids;
    }

    private static boolean containsAny(String msg, String... keys) {
        for (String k : keys) {
            if (msg.contains(k.toLowerCase(Locale.ROOT)) || msg.contains(k)) {
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${ekina.reminders.poll-ms:30000}")
    public synchronized void tick() {
        if (!enabled) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Instant nowInst = Instant.now();
        for (ReminderRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            try {
                Instant next = rule.getNextFireAt() == null ? Instant.EPOCH : Instant.parse(rule.getNextFireAt());
                if (next.isAfter(nowInst)) {
                    continue;
                }
                // Do not stack: an unacked card already owns this rule — wait for owner ack/snooze.
                if (hasPendingForRule(rule.getUserId(), rule.getId())) {
                    continue;
                }
                fire(rule, now);
                rule.setLastFiredAt(nowInst.toString());
                if ("once".equalsIgnoreCase(rule.getKind())) {
                    rule.setEnabled(false);
                    rule.setNextFireAt(null);
                } else {
                    // Schedule next strictly from now
                    rule.setNextFireAt(computeNextFire(rule, now.plusSeconds(5)).toInstant().toString());
                }
            } catch (Exception e) {
                System.err.println("[ReminderService] tick rule " + rule.getId() + ": " + e.getMessage());
            }
        }
        save();
    }

    private void fire(ReminderRule rule, ZonedDateTime now) {
        // Collapse previous pending cards for this rule
        supersedePendingForRule(rule.getUserId(), rule.getId(), null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ruleId", rule.getId());
        payload.put("title", rule.getTitle());
        payload.put("actions", List.of("ack", "snooze_10m", "snooze_30m", "snooze_1h"));
        payload.put("firedAt", Instant.now().toString());
        String text = (rule.getBody() == null || rule.getBody().isBlank())
                ? ("主人，提醒：" + rule.getTitle())
                : rule.getBody();
        if (!text.contains("主人")) {
            text = "主人～ " + text + " (˶ᵔ ᵕ ᵔ˶)";
        }
        channelService.append(rule.getUserId(), "agent", "reminder", text, payload, "pending");
        System.out.println("[ReminderService] fired " + rule.getId() + " for " + rule.getUserId()
                + " next will be after schedule");
    }

    private void seedDefaults(String userId) {
        ReminderRule stand = new ReminderRule();
        stand.setId("rem_standup");
        stand.setUserId(userId);
        stand.setTitle("起来走走");
        stand.setBody("主人该起来活动一下啦，坐太久对身体不好哦～ (˶ᵔ ᵕ ᵔ˶)");
        stand.setKind("interval");
        stand.setEveryMinutes(45);
        stand.setEnabled(true);
        stand.setNextFireAt(ZonedDateTime.now(ZONE).plusMinutes(45).toInstant().toString());
        rules.add(stand);

        ReminderRule med = new ReminderRule();
        med.setId("rem_medicine");
        med.setUserId(userId);
        med.setTitle("吃药");
        med.setBody("主人，到吃药的时间啦，别忘了哦～");
        med.setKind("daily_times");
        med.setCron("09:00,21:00");
        med.setEnabled(true);
        med.setNextFireAt(computeNextFire(med, ZonedDateTime.now(ZONE)).toInstant().toString());
        rules.add(med);
    }

    ZonedDateTime computeNextFire(ReminderRule rule, ZonedDateTime after) {
        String kind = rule.getKind() == null ? "interval" : rule.getKind().toLowerCase(Locale.ROOT);
        ZonedDateTime base = after.withZoneSameInstant(ZONE);
        if ("interval".equals(kind)) {
            int mins = rule.getEveryMinutes() == null ? 45 : Math.max(1, rule.getEveryMinutes());
            return base.plusMinutes(mins);
        }
        if ("once".equals(kind) && rule.getOnceAt() != null) {
            return ZonedDateTime.ofInstant(Instant.parse(rule.getOnceAt()), ZONE);
        }
        // daily_times: "09:00,21:00" in Asia/Shanghai
        if (rule.getCron() != null && rule.getCron().contains(":")) {
            String[] parts = rule.getCron().split(",");
            LocalDate day = base.toLocalDate();
            for (int d = 0; d < 3; d++) {
                LocalDate date = day.plusDays(d);
                for (String p : parts) {
                    String t = p.trim();
                    if (t.isEmpty()) continue;
                    try {
                        LocalTime lt = LocalTime.parse(t, DateTimeFormatter.ofPattern("H:mm"));
                        ZonedDateTime candidate = ZonedDateTime.of(date, lt, ZONE);
                        if (candidate.isAfter(base)) {
                            return candidate;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return base.plusHours(1);
    }

    private synchronized void load() {
        Path file = rulesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<ReminderRule> loaded = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<ReminderRule>>() {});
            rules.clear();
            if (loaded != null) {
                rules.addAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[ReminderService] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path file = rulesFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[ReminderService] save failed: " + e.getMessage());
        }
    }
}
