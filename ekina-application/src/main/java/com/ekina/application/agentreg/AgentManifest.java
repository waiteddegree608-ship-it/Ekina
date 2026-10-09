package com.ekina.application.agentreg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistable Ekina agent identity + capability packs.
 */
public class AgentManifest {
    private String id;
    private String displayName;
    private String signature;
    private String avatarUrl;
    private boolean enabled = true;
    private String threadId;
    private List<String> capabilityPacks = new ArrayList<>();
    /** life | executor | custom — drives prompt flavor */
    private String personaProfile = "custom";
    private boolean preset;
    private String emotionProfile; // e.g. agent
    private String lastPreview;
    private String lastTs;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public List<String> getCapabilityPacks() { return capabilityPacks; }
    public void setCapabilityPacks(List<String> capabilityPacks) {
        this.capabilityPacks = capabilityPacks == null ? new ArrayList<>() : new ArrayList<>(capabilityPacks);
    }
    public String getPersonaProfile() { return personaProfile; }
    public void setPersonaProfile(String personaProfile) { this.personaProfile = personaProfile; }
    public boolean isPreset() { return preset; }
    public void setPreset(boolean preset) { this.preset = preset; }
    public String getEmotionProfile() { return emotionProfile; }
    public void setEmotionProfile(String emotionProfile) { this.emotionProfile = emotionProfile; }
    public String getLastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
    public String getLastTs() { return lastTs; }
    public void setLastTs(String lastTs) { this.lastTs = lastTs; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("displayName", displayName == null ? "" : displayName);
        m.put("signature", signature == null ? "" : signature);
        m.put("avatarUrl", avatarUrl == null ? "" : avatarUrl);
        m.put("enabled", enabled);
        m.put("threadId", threadId == null ? "" : threadId);
        m.put("capabilityPacks", capabilityPacks == null ? List.of() : new ArrayList<>(capabilityPacks));
        m.put("personaProfile", personaProfile == null ? "custom" : personaProfile);
        m.put("preset", preset);
        m.put("emotionProfile", emotionProfile == null ? "" : emotionProfile);
        m.put("lastPreview", lastPreview == null ? "" : lastPreview);
        m.put("lastTs", lastTs == null ? "" : lastTs);
        // IM compat aliases
        m.put("contactId", id == null ? "" : id);
        m.put("personaKey", personaProfile == null ? "custom" : personaProfile);
        m.put("toolProfile", packsHint());
        return m;
    }

    private String packsHint() {
        if (capabilityPacks == null || capabilityPacks.isEmpty()) return "custom";
        if (capabilityPacks.contains(CapabilityPackCatalog.LIFE)) return "life";
        if (capabilityPacks.contains(CapabilityPackCatalog.EXECUTOR)) return "executor";
        return String.join("+", capabilityPacks);
    }
}
