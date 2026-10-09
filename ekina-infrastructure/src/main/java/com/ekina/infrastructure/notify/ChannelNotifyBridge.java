package com.ekina.infrastructure.notify;

import com.ekina.application.channel.ChannelEventHub;
import com.ekina.application.channel.ChannelMessage;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Forwards important channel messages to Windows toast notifications.
 */
@Component
public class ChannelNotifyBridge {

    @Autowired
    private ChannelEventHub eventHub;

    @Autowired
    private WindowsToastNotifier toastNotifier;

    @PostConstruct
    public void bind() {
        eventHub.subscribe(this::onMessage);
    }

    private void onMessage(ChannelMessage msg) {
        if (msg == null || msg.getType() == null) return;
        String type = msg.getType();
        String sender = msg.getSender() == null ? "" : msg.getSender();
        if (payloadSilent(msg)) return;

        if ("reminder".equals(type)) {
            toastNotifier.notify("伊姬娜 · 提醒", clip(msg.getText() == null ? type : msg.getText(), 180));
            return;
        }

        // Actionable popup dialogs: real modal Yes/No that drives the decision back to the backend.
        if ("permission_request".equals(type) || "plan".equals(type)) {
            String title = "permission_request".equals(type) ? "伊姬娜 · 权限申请" : "伊姬娜 · 请选择方案";
            if ("permission_request".equals(type)) {
                toastNotifier.showPermissionDialog(msg.getId(), msg.getUserId(), title,
                        msg.getText() == null ? type : msg.getText());
            } else {
                toastNotifier.showPlanDialog(msg.getId(), msg.getUserId(), title,
                        msg.getText() == null ? type : msg.getText());
            }
            return;
        }

        // WeChat-style: proactive companion chat bubbles also toast
        if ("chat".equals(type) && ("agent".equals(sender) || "system".equals(sender))) {
            // Skip HTTP echo mirrors — UI already shows them
            if (msg.getPayload() != null && (
                    Boolean.TRUE.equals(msg.getPayload().get("echo"))
                            || "chat_api".equals(String.valueOf(msg.getPayload().getOrDefault("source", ""))))) {
                return;
            }
            boolean surprise = msg.getPayload() != null && Boolean.TRUE.equals(msg.getPayload().get("surprise"));
            boolean report = msg.getPayload() != null && (
                    Boolean.TRUE.equals(msg.getPayload().get("report"))
                            || "report".equalsIgnoreCase(String.valueOf(msg.getPayload().getOrDefault("kind", ""))));
            String contact = "";
            if (msg.getPayload() != null && msg.getPayload().get("contactId") != null) {
                contact = String.valueOf(msg.getPayload().get("contactId")).trim().toLowerCase();
            }
            String thread = msg.getThreadId() == null ? "" : msg.getThreadId();
            boolean agentIm = "agent".equals(contact) || "contact_agent".equals(thread)
                    || thread.startsWith("contact_agent");
            String who = agentIm ? "代理人" : "伊姬娜";
            String title = report ? (who + " · 结果汇报")
                    : (surprise ? (who + " · 小惊喜") : who);
            toastNotifier.notify(title, clip(msg.getText(), 160));
        }
    }

    private static boolean payloadSilent(ChannelMessage msg) {
        if (msg.getPayload() == null) return false;
        Object s = msg.getPayload().get("silent");
        return Boolean.TRUE.equals(s) || "true".equalsIgnoreCase(String.valueOf(s));
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, Math.max(0, n - 1)) + "…";
    }
}
