package com.ekina.application.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A high-level goal from the owner, decomposed by Chief into a DAG of {@link MissionNode}s.
 */
public class Mission {
    private String id;
    private String userId;
    private String goal;
    /** planning | running | succeeded | failed | cancelled */
    private String status;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
    private String summary;
    private List<MissionNode> nodes = new ArrayList<>();
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<MissionNode> getNodes() { return nodes; }
    public void setNodes(List<MissionNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) {
        this.meta = meta == null ? new LinkedHashMap<>() : meta;
    }
}
