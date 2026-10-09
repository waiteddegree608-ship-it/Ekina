package com.ekina.application.permission;

import java.util.Map;

public class CapabilityGrant {
    private String id;
    private String kind;
    private String userId;
    private String status; // approved | revoked | expired
    private String grantedAt;
    private String expiresAt;
    private Map<String, Object> scope;
    private String note;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGrantedAt() { return grantedAt; }
    public void setGrantedAt(String grantedAt) { this.grantedAt = grantedAt; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public Map<String, Object> getScope() { return scope; }
    public void setScope(Map<String, Object> scope) { this.scope = scope; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
