package com.ekina.application.channel;

import java.util.Map;

/**
 * Companion channel message (chat / cards).
 */
public class ChannelMessage {
    private String id;
    private String userId;
    private String ts;
    private String sender; // user | agent | system
    private String type;   // chat | plan | permission_request | reminder | job_status | system
    private String text;
    private Map<String, Object> payload;
    private String status; // pending | done | cancelled | acked | snoozed
    /** Companion project thread (default: daily). */
    private String threadId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
}
