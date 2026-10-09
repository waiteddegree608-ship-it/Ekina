package com.ekina.application.reminder;

public class ReminderRule {
    private String id;
    private String userId;
    private String title;
    private String body;
    private String kind; // interval | cron | once
    private Integer everyMinutes;
    private String cron; // simple: "HH:mm" daily multi "HH:mm,HH:mm" or full cron 5-field
    private String onceAt; // ISO instant
    private boolean enabled = true;
    private String nextFireAt; // ISO
    private String lastFiredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getEveryMinutes() { return everyMinutes; }
    public void setEveryMinutes(Integer everyMinutes) { this.everyMinutes = everyMinutes; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getOnceAt() { return onceAt; }
    public void setOnceAt(String onceAt) { this.onceAt = onceAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(String nextFireAt) { this.nextFireAt = nextFireAt; }
    public String getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(String lastFiredAt) { this.lastFiredAt = lastFiredAt; }
}
