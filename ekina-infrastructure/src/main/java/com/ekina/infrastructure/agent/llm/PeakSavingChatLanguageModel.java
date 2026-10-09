package com.ekina.infrastructure.agent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * During official DeepSeek peak hours, never call DeepSeek — use the cheap OpenCode model.
 * Off-peak keeps DeepSeek first, with OpenCode as quota/region fallback.
 */
public class PeakSavingChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel cheap;
    private final FallbackChatLanguageModel offPeak;
    private final String role;
    private final boolean skipDeepseekPeak;
    private final AtomicReference<String> lastWindow = new AtomicReference<>();

    public PeakSavingChatLanguageModel(
            ChatLanguageModel deepseek,
            ChatLanguageModel cheap,
            String role,
            boolean skipDeepseekPeak
    ) {
        this.cheap = cheap;
        this.role = role == null ? "chat" : role;
        this.skipDeepseekPeak = skipDeepseekPeak;
        this.offPeak = new FallbackChatLanguageModel(deepseek, cheap, role);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        if (useCheapOnly()) {
            return cheap.generate(messages);
        }
        return offPeak.generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return generate(messages);
        }
        if (useCheapOnly()) {
            return cheap.generate(messages, toolSpecifications);
        }
        return offPeak.generate(messages, toolSpecifications);
    }

    private boolean useCheapOnly() {
        boolean peak = skipDeepseekPeak && DeepSeekPeakHours.isPeakNow();
        String window = peak ? "peak-skip-deepseek" : "off-peak-deepseek";
        String prev = lastWindow.getAndSet(window);
        if (!window.equals(prev)) {
            System.out.println("[LlmPeakSave] role=" + role + " " + DeepSeekPeakHours.describeNow()
                    + " → " + (peak ? "OpenCode only (no DeepSeek)" : "DeepSeek first"));
        }
        return peak;
    }
}
