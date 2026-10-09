package com.ekina.infrastructure.agent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routes to OpenAI-compatible chat/completions or Anthropic-compatible /messages
 * based on model id and optional force protocol.
 */
public class RoutingChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel openAiModel;
    private final ChatLanguageModel messagesModel;
    private final String modelName;
    private final String forceProtocol; // auto | openai | messages
    private final Set<String> messagesModels;

    public RoutingChatLanguageModel(
            ChatLanguageModel openAiModel,
            ChatLanguageModel messagesModel,
            String modelName,
            String forceProtocol,
            List<String> messagesModels
    ) {
        this.openAiModel = openAiModel;
        this.messagesModel = messagesModel;
        this.modelName = modelName == null ? "" : modelName;
        this.forceProtocol = forceProtocol == null ? "auto" : forceProtocol.trim().toLowerCase(Locale.ROOT);
        this.messagesModels = messagesModels == null
                ? Set.of()
                : messagesModels.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private ChatLanguageModel pick() {
        if ("messages".equals(forceProtocol) || "anthropic".equals(forceProtocol)) {
            return messagesModel;
        }
        if ("openai".equals(forceProtocol) || "chat".equals(forceProtocol)) {
            return openAiModel;
        }
        String m = modelName.toLowerCase(Locale.ROOT);
        if (m.startsWith("claude") || m.contains("claude") || messagesModels.contains(m)) {
            return messagesModel;
        }
        // Default Console Go chat/completions (qwen/kimi/glm/deepseek/…)
        return openAiModel;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return pick().generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        ChatLanguageModel model = pick();
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return model.generate(messages);
        }
        return model.generate(messages, toolSpecifications);
    }
}
