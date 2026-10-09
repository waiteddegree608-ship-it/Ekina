package com.ekina.infrastructure.db.entity;

import com.ekina.domain.agent.WeChatOutboundMsg;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "wechat_outbound_msgs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeChatOutboundMsgEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "message_type", length = 20, nullable = false)
    private String messageType; // "TEXT" or "AUDIO"

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Lob
    @Column(name = "payload")
    private byte[] payload;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WeChatOutboundMsg.WeChatMsgStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_send_time", nullable = false)
    private LocalDateTime nextSendTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public WeChatOutboundMsg toDomain() {
        WeChatOutboundMsg msg = new WeChatOutboundMsg();
        msg.setId(this.id);
        msg.setUserId(this.userId);
        msg.setMessageType(this.messageType);
        msg.setContent(this.content);
        msg.setPayload(this.payload);
        msg.setPriority(this.priority);
        msg.setStatus(this.status);
        msg.setRetryCount(this.retryCount);
        msg.setNextSendTime(this.nextSendTime);
        msg.setCreatedAt(this.createdAt);
        msg.setErrorMessage(this.errorMessage);
        return msg;
    }

    public static WeChatOutboundMsgEntity fromDomain(WeChatOutboundMsg msg) {
        if (msg == null) {
            return null;
        }
        return WeChatOutboundMsgEntity.builder()
                .id(msg.getId())
                .userId(msg.getUserId())
                .messageType(msg.getMessageType())
                .content(msg.getContent())
                .payload(msg.getPayload())
                .priority(msg.getPriority())
                .status(msg.getStatus() != null ? msg.getStatus() : WeChatOutboundMsg.WeChatMsgStatus.PENDING)
                .retryCount(msg.getRetryCount())
                .nextSendTime(msg.getNextSendTime() != null ? msg.getNextSendTime() : LocalDateTime.now())
                .createdAt(msg.getCreatedAt() != null ? msg.getCreatedAt() : LocalDateTime.now())
                .errorMessage(msg.getErrorMessage())
                .build();
    }
}
