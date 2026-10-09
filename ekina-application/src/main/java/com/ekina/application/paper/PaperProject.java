package com.ekina.application.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One paper production job under AgentHome/papers/&lt;id&gt;/.
 */
public class PaperProject {
    private String id;
    private String userId;
    private String domain;
    private String venueTier;
    private String titleWorking;
    /** smoke | reproduce | research — see {@link PaperMode}. */
    private String mode = PaperMode.RESEARCH.name();
    /** Optional pinned GitHub repo for REPRO (mini-goal). */
    private String seedRepo;
    private String status; // running | succeeded | failed | cancelled
    private PaperStage stage;
    private String createdAt;
    private String updatedAt;
    private String finishedAt;
    private String rootPath;
    private List<String> stageLog = new ArrayList<>();
    private Map<String, Object> gateReport = new LinkedHashMap<>();
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getVenueTier() { return venueTier; }
    public void setVenueTier(String venueTier) { this.venueTier = venueTier; }
    public String getTitleWorking() { return titleWorking; }
    public void setTitleWorking(String titleWorking) { this.titleWorking = titleWorking; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getSeedRepo() { return seedRepo; }
    public void setSeedRepo(String seedRepo) { this.seedRepo = seedRepo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public PaperStage getStage() { return stage; }
    public void setStage(PaperStage stage) { this.stage = stage; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }
    public List<String> getStageLog() { return stageLog; }
    public void setStageLog(List<String> stageLog) {
        this.stageLog = stageLog == null ? new ArrayList<>() : stageLog;
    }
    public Map<String, Object> getGateReport() { return gateReport; }
    public void setGateReport(Map<String, Object> gateReport) {
        this.gateReport = gateReport == null ? new LinkedHashMap<>() : gateReport;
    }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) {
        this.meta = meta == null ? new LinkedHashMap<>() : meta;
    }
}
