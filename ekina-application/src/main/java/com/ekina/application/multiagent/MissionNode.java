package com.ekina.application.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One DAG node inside a {@link Mission}, assigned to CODER or CLAW.
 */
public class MissionNode {
    private String id;
    private AgentRole assignee;
    private String title;
    private String brief;
    private List<String> dependsOn = new ArrayList<>();
    /** pending | queued | running | succeeded | failed | blocked | cancelled */
    private String status;
    private String taskId;
    private String result;
    private String error;
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public AgentRole getAssignee() { return assignee; }
    public void setAssignee(AgentRole assignee) { this.assignee = assignee; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) {
        this.meta = meta == null ? new LinkedHashMap<>() : meta;
    }
}
