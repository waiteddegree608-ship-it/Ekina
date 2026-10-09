package com.ekina.domain.agent;

import java.util.List;

/**
 * Domain interface defining LLM Chat and Embedding capabilities.
 * Implemented at the infrastructure layer using Spring AI or LangChain4j.
 */
public interface LlmService {

    /**
     * Executes a simple single-turn user prompt.
     */
    String chat(String userPrompt);

    /**
     * Executes a multi-turn chat completions request.
     */
    String chat(List<ChatMessage> messages);

    /**
     * Generates a semantic vector embedding (typically 1024 dimensions) for the input text.
     */
    float[] embed(String text);
}
