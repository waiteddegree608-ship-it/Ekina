package com.ekina.application.multiagent;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentTask {
    private String id;
    private String userId;
    private AgentRole assignee;
    private String title;
    private String brief;
    /** queued | running | succeeded | failed | cancelled */
    private String status;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
    private String result;
    private String error;
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public AgentRole getAssignee() { return assignee; }
    public void setAssignee(AgentRole assignee) { this.assignee = assignee; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta == null ? new LinkedHashMap<>() : meta; }
}
