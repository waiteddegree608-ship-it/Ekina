package com.ekina.infrastructure.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic-compatible client for OpenCode Console Go:
 * {@code POST {base}/messages} with {@code x-api-key} (Bearer alone returns 401).
 * <p>
 * Tool use is translated to Anthropic {@code tools}/{@code tool_use} shape when tools are provided.
 */
public class OpenCodeMessagesChatModel implements ChatLanguageModel {

    private final String apiKey;
    private final String messagesUrl;
    private final String modelName;
    private final Double temperature;
    private final Duration timeout;
    private final String sessionId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public OpenCodeMessagesChatModel(String apiKey, String baseUrl, String modelName, Double temperature, Duration timeout) {
        this(apiKey, baseUrl, modelName, temperature, timeout, "ekina-opencode-user-1");
    }

    public OpenCodeMessagesChatModel(String apiKey, String baseUrl, String modelName, Double temperature,
                                     Duration timeout, String sessionId) {
        this.apiKey = apiKey;
        this.messagesUrl = joinUrl(baseUrl, "messages");
        this.modelName = modelName;
        this.temperature = temperature;
        this.timeout = timeout == null ? Duration.ofSeconds(300) : timeout;
        this.sessionId = (sessionId == null || sessionId.isBlank()) ? "ekina-opencode-user-1" : sessionId.trim();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", modelName);
            root.put("max_tokens", 4096);
            if (temperature != null) {
                root.put("temperature", temperature);
            }

            String system = extractSystem(messages);
            if (system != null && !system.isBlank()) {
                root.put("system", system);
            }

            root.set("messages", toAnthropicMessages(messages));

            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                root.set("tools", toAnthropicTools(toolSpecifications));
            }

            String body = mapper.writeValueAsString(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("x-opencode-session", sessionId)
                    .header("User-Agent", "ekina-research-agent/1.0")
                    // some gateways also accept bearer; harmless when paired with x-api-key
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenCode /messages HTTP " + response.statusCode() + ": " + trim(response.body(), 500));
            }
            return Response.from(parseAiMessage(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenCode /messages failed: " + e.getMessage(), e);
        }
    }

    private AiMessage parseAiMessage(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            // some proxies wrap OpenAI-style
            String text = root.path("choices").path(0).path("message").path("content").asText(null);
            if (text != null) {
                return AiMessage.from(text);
            }
            return AiMessage.from(trim(body, 2000));
        }

        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                String id = block.path("id").asText(UUID.randomUUID().toString());
                String name = block.path("name").asText("");
                String args = block.path("input").isMissingNode() || block.path("input").isNull()
                        ? "{}"
                        : mapper.writeValueAsString(block.path("input"));
                toolRequests.add(ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(args)
                        .build());
            }
        }
        if (!toolRequests.isEmpty()) {
            return AiMessage.from(toolRequests);
        }
        return AiMessage.from(text.toString());
    }

    private ArrayNode toAnthropicMessages(List<ChatMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue; // system handled separately
            }
            if (msg instanceof UserMessage user) {
                ObjectNode m = mapper.createObjectNode();
                m.put("role", "user");
                m.put("content", user.singleText());
                arr.add(m);
            } else if (msg instanceof AiMessage ai) {
                ObjectNode m = mapper.createObjectNode();
                m.put("role", "assistant");
                if (ai.hasToolExecutionRequests()) {
                    ArrayNode content = mapper.createArrayNode();
                    if (ai.text() != null && !ai.text().isBlank()) {
                        ObjectNode t = mapper.createObjectNode();
                        t.put("type", "text");
                        t.put("text", ai.text());
                        content.add(t);
                    }
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        ObjectNode toolUse = mapper.createObjectNode();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", req.id() == null ? UUID.randomUUID().toString() : req.id());
                        toolUse.put("name", req.name());
                        try {
                            toolUse.set("input", mapper.readTree(req.arguments() == null ? "{}" : req.arguments()));
                        } catch (Exception e) {
                            toolUse.putObject("input").put("raw", req.arguments());
                        }
                        content.add(toolUse);
                    }
                    m.set("content", content);
                } else {
                    m.put("content", ai.text() == null ? "" : ai.text());
                }
                arr.add(m);
            } else if (msg instanceof ToolExecutionResultMessage tool) {
                // Anthropic expects user role with tool_result blocks
                ObjectNode m = mapper.createObjectNode();
                m.put("role", "user");
                ArrayNode content = mapper.createArrayNode();
                ObjectNode tr = mapper.createObjectNode();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", tool.id());
                tr.put("content", tool.text() == null ? "" : tool.text());
                content.add(tr);
                m.set("content", content);
                arr.add(m);
            }
        }
        // Anthropic requires non-empty messages; ensure at least one user turn
        if (arr.isEmpty()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", "user");
            m.put("content", "(empty)");
            arr.add(m);
        }
        return arr;
    }

    private ArrayNode toAnthropicTools(List<ToolSpecification> specs) {
        ArrayNode tools = mapper.createArrayNode();
        for (ToolSpecification spec : specs) {
            ObjectNode t = mapper.createObjectNode();
            t.put("name", spec.name());
            t.put("description", spec.description() == null ? spec.name() : spec.description());
            ObjectNode inputSchema = mapper.createObjectNode();
            inputSchema.put("type", "object");
            ObjectNode properties = mapper.createObjectNode();
            ArrayNode required = mapper.createArrayNode();
            if (spec.parameters() != null && spec.parameters().properties() != null) {
                spec.parameters().properties().forEach((name, schema) -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("type", "string");
                    p.put("description", name);
                    properties.set(name, p);
                });
                if (spec.parameters().required() != null) {
                    for (String r : spec.parameters().required()) {
                        required.add(r);
                    }
                }
            }
            inputSchema.set("properties", properties);
            if (!required.isEmpty()) {
                inputSchema.set("required", required);
            }
            t.set("input_schema", inputSchema);
            tools.add(t);
        }
        return tools;
    }

    private static String extractSystem(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage system) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(system.text());
            }
        }
        return sb.toString();
    }

    private static String joinUrl(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        // base may be .../v1 or .../v1/
        return b + "/" + path;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
