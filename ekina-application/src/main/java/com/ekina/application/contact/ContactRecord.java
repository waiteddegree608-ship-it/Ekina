package com.ekina.application.contact;

import java.util.Map;

/**
 * IM contact (AI character the owner chats with).
 */
public class ContactRecord {
    private String id;
    private String displayName;
    private String signature;
    private String threadId;
    private String personaKey;
    private String toolProfile; // life | executor
    private String avatarUrl;
    private String lastPreview;
    private String lastTs;

    public ContactRecord() {}

    public ContactRecord(String id, String displayName, String signature, String threadId,
                         String personaKey, String toolProfile, String avatarUrl) {
        this.id = id;
        this.displayName = displayName;
        this.signature = signature;
        this.threadId = threadId;
        this.personaKey = personaKey;
        this.toolProfile = toolProfile;
        this.avatarUrl = avatarUrl;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getPersonaKey() { return personaKey; }
    public void setPersonaKey(String personaKey) { this.personaKey = personaKey; }
    public String getToolProfile() { return toolProfile; }
    public void setToolProfile(String toolProfile) { this.toolProfile = toolProfile; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getLastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
    public String getLastTs() { return lastTs; }
    public void setLastTs(String lastTs) { this.lastTs = lastTs; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("displayName", displayName == null ? "" : displayName);
        m.put("signature", signature == null ? "" : signature);
        m.put("threadId", threadId == null ? "" : threadId);
        m.put("personaKey", personaKey == null ? "" : personaKey);
        m.put("toolProfile", toolProfile == null ? "" : toolProfile);
        m.put("avatarUrl", avatarUrl == null ? "" : avatarUrl);
        m.put("lastPreview", lastPreview == null ? "" : lastPreview);
        m.put("lastTs", lastTs == null ? "" : lastTs);
        return m;
    }
}
