package com.ekina.infrastructure.agent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * Try the preferred provider first; on gateway/quota/region failure use the other.
 */
public class FallbackChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel primary;
    private final ChatLanguageModel secondary;
    private final String role;

    public FallbackChatLanguageModel(ChatLanguageModel primary, ChatLanguageModel secondary, String role) {
        this.primary = primary;
        this.secondary = secondary;
        this.role = role == null ? "chat" : role;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            return primary.generate(messages);
        } catch (RuntimeException e) {
            if (secondary == null || !shouldFallback(e)) {
                throw e;
            }
            System.err.println("[LlmFallback] role=" + role + " primary failed: " + clip(e.getMessage())
                    + " — switching provider");
            return secondary.generate(messages);
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return generate(messages);
        }
        try {
            return primary.generate(messages, toolSpecifications);
        } catch (RuntimeException e) {
            if (secondary == null || !shouldFallback(e)) {
                throw e;
            }
            System.err.println("[LlmFallback] role=" + role + " tools primary failed: " + clip(e.getMessage())
                    + " — switching provider");
            return secondary.generate(messages, toolSpecifications);
        }
    }

    private static boolean shouldFallback(Throwable e) {
        String m = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase();
        return m.contains("401") || m.contains("402") || m.contains("403") || m.contains("429")
                || m.contains("500") || m.contains("502") || m.contains("503")
                || m.contains("region") || m.contains("quota") || m.contains("balance")
                || m.contains("timeout") || m.contains("timed out") || m.contains("unauthorized")
                || m.contains("insufficient");
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 180 ? s.substring(0, 180) : s;
    }
}
