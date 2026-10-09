package com.ekina.infrastructure.agent;

import com.ekina.domain.agent.ChatMessage;
import com.ekina.domain.agent.LlmService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Infrastructure implementation of the domain LlmService using LangChain4j ChatLanguageModel
 * (for longer thinking timeouts) and Spring AI EmbeddingModel.
 */
@Service
public class LlmServiceImpl implements LlmService {

    @Autowired
    private ChatLanguageModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Override
    public String chat(String userPrompt) {
        return chatModel.generate(userPrompt);
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<dev.langchain4j.data.message.ChatMessage> lcMessages = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg.getRole() == null || msg.getContent() == null) {
                continue;
            }
            switch (msg.getRole()) {
                case SYSTEM:
                    lcMessages.add(new SystemMessage(msg.getContent()));
                    break;
                case USER:
                    lcMessages.add(new UserMessage(msg.getContent()));
                    break;
                case ASSISTANT:
                    lcMessages.add(new AiMessage(msg.getContent()));
                    break;
                default:
                    lcMessages.add(new UserMessage(msg.getContent()));
                    break;
            }
        }
        return chatModel.generate(lcMessages).content().text();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }
        return embeddingModel.embed(text);
    }
}

