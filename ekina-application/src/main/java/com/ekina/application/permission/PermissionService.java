package com.ekina.application.permission;

import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Approve/deny companion permission_request cards and apply side effects.
 */
@Service
public class PermissionService {

    @Autowired
    private ChannelService channelService;

    @Autowired
    private CapabilityService capabilityService;

    @Autowired
    private HomeQuotaService homeQuotaService;

    /**
     * Natural language: 同意 / 批准 / 可以 / ok → approve all pending permission_request (and optional plan) cards.
     */
    public Map<String, Object> handleNaturalLanguagePermission(String userId, String userMessage) {
        Map<String, Object> out = new HashMap<>();
        out.put("handled", false);
        out.put("approved", 0);
        out.put("denied", 0);
        if (userMessage == null || userMessage.isBlank()) {
            return out;
        }
        String raw = userMessage.trim();
        String msg = raw.toLowerCase(Locale.ROOT);
        // Exact / very short permission replies (must be reliable — never send these to LLM alone)
        boolean exactApprove = raw.equals("同意") || raw.equals("批准") || raw.equals("可以")
                || raw.equals("好的") || raw.equals("行") || raw.equals("准了") || raw.equals("允许")
                || raw.equalsIgnoreCase("ok") || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("approve")
                || raw.equals("同意了") || raw.equals("批准了") || raw.equals("给你权限") || raw.equals("授权");
        boolean exactDeny = raw.equals("拒绝") || raw.equals("不同意") || raw.equals("不准")
                || raw.equals("不行") || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("deny");
        boolean approve = exactApprove || containsAny(msg, "我同意", "我批准", "批准权限", "同意权限", "全部同意", "都同意");
        boolean deny = exactDeny || containsAny(msg, "我拒绝", "拒绝权限", "全部拒绝");
        if (!approve && !deny) {
            return out;
        }

        // Pending across all threads (list() is capped + thread-scoped and misses old cards)
        List<ChannelMessage> recent = channelService.listPending(userId);
        List<String> done = new ArrayList<>();
        for (ChannelMessage m : recent) {
            if (m.getStatus() == null || !"pending".equalsIgnoreCase(m.getStatus())) {
                continue;
            }
            if (!"permission_request".equals(m.getType()) && !"plan".equals(m.getType())) {
                continue;
            }
            try {
                Map<String, Object> r = decide(userId, m.getId(), deny ? "denied" : "approved", 168, null);
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    done.add(m.getId() + ":" + (deny ? "denied" : "approved") + ":" + r.get("applied"));
                } else {
                    System.err.println("[Permission] decide failed for " + m.getId() + ": " + r.get("error"));
                }
            } catch (Exception e) {
                System.err.println("[Permission] decide exception for " + m.getId() + ": " + e.getMessage());
            }
        }
        // Even if zero cards, treat pure "同意" as handled so we don't spin the LLM
        boolean pure = exactApprove || exactDeny || raw.length() <= 8;
        out.put("handled", pure || !done.isEmpty());
        out.put("approved", deny ? 0 : done.size());
        out.put("denied", deny ? done.size() : 0);
        out.put("details", done);
        System.out.println("[Permission] NL " + (deny ? "deny" : "approve") + " handled=" + out.get("handled")
                + " count=" + done.size() + " msg=" + raw);
        return out;
    }

    private static boolean containsAny(String msg, String... keys) {
        for (String k : keys) {
            if (msg.contains(k.toLowerCase(Locale.ROOT)) || msg.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param decision approved | denied
     * @param ttlHours grant TTL for capabilities
     * @param scopeOverride optional scope merge (e.g. ssh host/user/keyPath/workdir)
     */
    public Map<String, Object> decide(String userId, String messageId, String decision,
                                      Integer ttlHours, Map<String, Object> scopeOverride) {
        Map<String, Object> result = new HashMap<>();
        // Must search all threads — pending cards are often older than list(limit=200)
        ChannelMessage msg = channelService.findById(userId, messageId);
        if (msg == null) {
            result.put("ok", false);
            result.put("error", "message not found");
            return result;
        }
        if (!"permission_request".equals(msg.getType()) && !"plan".equals(msg.getType())) {
            // still allow marking done
        }

        boolean approved = "approved".equalsIgnoreCase(decision) || "approve".equalsIgnoreCase(decision)
                || "selected".equalsIgnoreCase(decision);
        // Use done/cancelled so listPending (status==pending) drops the card immediately
        String status = approved ? "done" : "cancelled";
        Map<String, Object> patch = new HashMap<>();
        patch.put("decision", approved ? "approved" : "denied");
        if (scopeOverride != null) {
            patch.put("scope_override", scopeOverride);
        }

        ChannelMessage updated = channelService.updateStatus(userId, messageId, status, patch);
        if (updated == null) {
            result.put("ok", false);
            result.put("error", "update failed");
            return result;
        }
        result.put("message", updated);

        if (!approved) {
            result.put("ok", true);
            result.put("applied", "none");
            return result;
        }

        Map<String, Object> payload = msg.getPayload() == null ? Map.of() : msg.getPayload();
        String kind = String.valueOf(payload.getOrDefault("kind",
                payload.getOrDefault("selected_id", "generic")));

        Map<String, Object> scope = new HashMap<>();
        if (payload.get("scope") instanceof Map<?, ?> s) {
            s.forEach((k, v) -> scope.put(String.valueOf(k), v));
        }
        if (scopeOverride != null) {
            scope.putAll(scopeOverride);
        }

        // Side effects
        if ("disk_quota".equalsIgnoreCase(kind)) {
            long need = 10;
            Object n = scope.get("needGb");
            if (n == null && payload.get("needGb") != null) n = payload.get("needGb");
            if (n instanceof Number num) need = num.longValue();
            Map<String, Object> st = homeQuotaService.status();
            long cur = ((Number) st.get("quotaGb")).longValue();
            homeQuotaService.setQuotaGb(cur + Math.max(1, need));
            result.put("applied", "disk_quota+" + need);
            capabilityService.grant(userId, "disk_quota", Map.of("addedGb", need), ttlHours == null ? 720 : ttlHours,
                    "quota expanded");
        } else if ("ssh_remote".equalsIgnoreCase(kind) || "screen_peek".equalsIgnoreCase(kind)
                || "write_outside_home".equalsIgnoreCase(kind) || "local_gpu".equalsIgnoreCase(kind)
                || "network".equalsIgnoreCase(kind)) {
            CapabilityGrant g = capabilityService.grant(userId, kind, scope, ttlHours == null ? 48 : ttlHours,
                    "from card " + messageId);
            result.put("applied", "capability:" + g.getId());
            result.put("grant", g);
        } else if ("plan".equals(msg.getType()) || payload.containsKey("options")) {
            // plan selection: selected option id in decision/scope
            Object selected = scopeOverride != null ? scopeOverride.get("optionId") : payload.get("selected_id");
            result.put("applied", "plan_selected:" + selected);
            capabilityService.grant(userId, "plan_selection",
                    Map.of("optionId", selected == null ? "" : selected, "planMessageId", messageId),
                    ttlHours == null ? 24 : ttlHours, "plan choice");
        } else {
            CapabilityGrant g = capabilityService.grant(userId, kind, scope, ttlHours == null ? 24 : ttlHours, "generic");
            result.put("applied", "capability:" + g.getId());
            result.put("grant", g);
        }

        String decisionLabel = approved ? "approved" : "denied";
        channelService.append(userId, "system", "system",
                "权限已处理：" + decisionLabel + " (" + kind + ")", result, "done");
        result.put("ok", true);
        return result;
    }
}
