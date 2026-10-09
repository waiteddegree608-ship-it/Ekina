package com.ekina.application.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-created companion project folder (Cursor-Agents style).
 */
public class ProjectRecord {
    private String id;
    private String title;
    /**
     * daily | portfolio | mission | paper | task
     * <p>User-facing dock shows portfolio / mission / paper.
     * {@code task} rows are Ekina's internal ledger (not shown to the owner).
     */
    private String sourceType;
    private String sourceId;
    private String threadId;
    private String userId;
    /**
     * Owning contact / agent id (ekina | agent | frederick | ops | …).
     * Projects are scoped by (userId, contactId) — never shared across contacts.
     */
    private String contactId;
    private String createdAt;
    private String updatedAt;
    private List<String> linkedTaskIds = new ArrayList<>();
    private Map<String, Object> meta = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getContactId() { return contactId; }
    public void setContactId(String contactId) { this.contactId = contactId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public List<String> getLinkedTaskIds() { return linkedTaskIds; }
    public void setLinkedTaskIds(List<String> linkedTaskIds) { this.linkedTaskIds = linkedTaskIds; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }
}
