package com.ekina.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain entity representing a chat message in the Agent conversation history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum MessageRole {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    private MessageRole role;
    private String content;
}
