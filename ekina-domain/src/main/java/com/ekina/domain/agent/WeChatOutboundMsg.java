package com.ekina.domain.agent;

import java.time.LocalDateTime;

public class WeChatOutboundMsg {

    public enum WeChatMsgStatus {
        PENDING,
        SENDING,
        SENT,
        FAILED
    }

    private Long id;
    private String userId;
    private String messageType; // "TEXT" or "AUDIO"
    private String content;
    private byte[] payload; // for binary audio bytes
    private int priority;
    private WeChatMsgStatus status;
    private int retryCount;
    private LocalDateTime nextSendTime;
    private LocalDateTime createdAt;
    private String errorMessage;

    public WeChatOutboundMsg() {}

    public WeChatOutboundMsg(String userId, String messageType, String content, byte[] payload) {
        this.userId = userId;
        this.messageType = messageType;
        this.content = content;
        this.payload = payload;
        this.priority = 0;
        this.status = WeChatMsgStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.nextSendTime = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public WeChatMsgStatus getStatus() {
        return status;
    }

    public void setStatus(WeChatMsgStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextSendTime() {
        return nextSendTime;
    }

    public void setNextSendTime(LocalDateTime nextSendTime) {
        this.nextSendTime = nextSendTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
