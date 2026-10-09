package com.ekina.bootstrap.config;

import com.ekina.infrastructure.agent.llm.FallbackChatLanguageModel;
import com.ekina.infrastructure.agent.llm.OpenCodeMessagesChatModel;
import com.ekina.infrastructure.agent.llm.PeakSavingChatLanguageModel;
import com.ekina.infrastructure.agent.llm.RoutingChatLanguageModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * OpenCode Zen dual-protocol wiring:
 * <ul>
 *   <li>OpenAI-compatible: {@code POST {base}/chat/completions} + Bearer</li>
 *   <li>Anthropic-compatible: {@code POST {base}/messages} + x-api-key</li>
 * </ul>
 */
@Configuration
public class LlmProviderConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String modelName;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private Double temperature;

    @Value("${ekina.embedding.dimensions:1024}")
    private int embeddingDimensions;

    /**
     * auto | openai | messages
     */
    @Value("${ekina.llm.protocol:auto}")
    private String protocol;

    /**
     * Comma-separated model ids that must use /messages (in addition to claude*).
     */
    @Value("${ekina.llm.messages-models:}")
    private String messagesModelsCsv;

    @Value("${ekina.llm.research-model:}")
    private String researchModelName;

    @Value("${ekina.llm.chief-provider:deepseek}")
    private String chiefProvider;

    @Value("${ekina.llm.research-provider:deepseek}")
    private String researchProvider;

    @Value("${ekina.deepseek.api-key:}")
    private String deepseekKey;

    @Value("${ekina.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBase;

    @Value("${ekina.deepseek.chat-model:deepseek-v4-flash}")
    private String deepseekChatModel;

    @Value("${ekina.deepseek.reasoner-model:deepseek-v4-pro}")
    private String deepseekReasonerModel;

    @Value("${ekina.llm.skip-deepseek-peak:true}")
    private boolean skipDeepseekPeak;

    @Value("${ekina.llm.peak-alt-model:glm-5.3}")
    private String peakAltModel;

    @Value("${ekina.llm.max-output-tokens:8192}")
    private int maxOutputTokens;

    @Bean
    @Primary
    public ChatLanguageModel langchain4jChatModel() {
        return routeRole("chief", chiefProvider, deepseekChatModel, 0.7, modelName);
    }

    @Bean(name = "researchChatModel")
    public ChatLanguageModel researchChatModel() {
        String opencodeResearch = (researchModelName == null || researchModelName.isBlank())
                ? modelName
                : researchModelName.trim();
        return routeRole("research", researchProvider, deepseekReasonerModel, 0.3, opencodeResearch);
    }

    /**
     * Preferred provider first; the other engine is a quota/region fallback.
     * DeepSeek official: CHIEF=chat (tools), RESEARCH=reasoner (writing/critic).
     * OpenCode Zen: CODER CLI / vision / fallback models.
     */
    private ChatLanguageModel routeRole(String role, String preferred, String deepseekModel,
                                        double deepseekTemp, String opencodeModel) {
        ChatLanguageModel zen = buildRoutingModel(cheapOpenCodeModel(opencodeModel), role + "/opencode");
        if (!hasDeepseek()) {
            System.out.println("[LlmProvider] role=" + role + " provider=opencode-only model=" + cheapOpenCodeModel(opencodeModel));
            return zen;
        }
        ChatLanguageModel ds = buildPlainOpenAi(deepseekKey, deepseekBase, deepseekModel, deepseekTemp);
        boolean wantDeepseek = preferred == null || preferred.isBlank()
                || "deepseek".equalsIgnoreCase(preferred.trim())
                || "auto".equalsIgnoreCase(preferred.trim());
        System.out.println("[LlmProvider] role=" + role
                + " preferred=" + (wantDeepseek ? "deepseek:" + deepseekModel : "opencode:" + cheapOpenCodeModel(opencodeModel))
                + " skipPeak=" + skipDeepseekPeak
                + " peakAlt=" + cheapOpenCodeModel(opencodeModel)
                + " fallback=" + (wantDeepseek ? "opencode" : "deepseek"));
        if (!wantDeepseek) {
            return new FallbackChatLanguageModel(zen, ds, role);
        }
        return new PeakSavingChatLanguageModel(ds, zen, role, skipDeepseekPeak);
    }

    private String cheapOpenCodeModel(String fallback) {
        String alt = peakAltModel == null ? "" : peakAltModel.trim();
        if (alt.isBlank() && fallback != null) {
            alt = fallback.trim();
        }
        if (alt.isBlank() || alt.toLowerCase().contains("deepseek")) {
            return "glm-5.3";
        }
        return alt;
    }

    /** OpenCode Go requires a stable x-opencode-session or chat/completions is rejected. */
    private static java.util.Map<String, String> openCodeGoHeaders(String role) {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("x-opencode-session", openCodeSessionId(role));
        h.put("User-Agent", "ekina-research-agent/1.0");
        return h;
    }

    private static String openCodeSessionId(String role) {
        String r = (role == null || role.isBlank()) ? "chief" : role.trim();
        return "ekina-" + r.replace('/', '-') + "-user-1";
    }

    private boolean hasDeepseek() {
        return deepseekKey != null && !deepseekKey.isBlank()
                && !deepseekKey.startsWith("your-") && !deepseekKey.contains("xxx");
    }

    private ChatLanguageModel buildPlainOpenAi(String key, String base, String name, double temp) {
        return OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(normalizeOpenAiBase(base))
                .modelName(name)
                .temperature(temp)
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private ChatLanguageModel buildRoutingModel(String name, String role) {
        String key = (apiKey == null || apiKey.isBlank()) ? "missing-opencode-api-key" : apiKey;
        String openAiBase = normalizeOpenAiBase(baseUrl);
        String messagesBase = stripTrailingSlash(openAiBase.replaceAll("/$", ""));

        ChatLanguageModel openAi = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(openAiBase)
                .modelName(name)
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .customHeaders(openCodeGoHeaders(role))
                .build();

        ChatLanguageModel messages = new OpenCodeMessagesChatModel(
                key,
                messagesBase,
                name,
                temperature,
                Duration.ofSeconds(300),
                openCodeSessionId(role)
        );

        List<String> msgModels = Arrays.stream(messagesModelsCsv == null ? new String[0] : messagesModelsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        System.out.println("[LlmProvider] role=" + role + " model=" + name
                + " protocol=" + protocol
                + " openaiBase=" + openAiBase
                + " messagesBase=" + messagesBase
                + " messagesModels=" + msgModels);

        return new RoutingChatLanguageModel(openAi, messages, name, protocol, msgModels);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return new LocalHashEmbeddingModel(embeddingDimensions);
    }

    private static String normalizeOpenAiBase(String baseUrl) {
        String resolvedBaseUrl = baseUrl;
        if (resolvedBaseUrl != null && !resolvedBaseUrl.trim().isEmpty()) {
            if (!resolvedBaseUrl.endsWith("/v1") && !resolvedBaseUrl.endsWith("/v1/")) {
                resolvedBaseUrl = resolvedBaseUrl.endsWith("/") ? resolvedBaseUrl + "v1/" : resolvedBaseUrl + "/v1/";
            } else if (resolvedBaseUrl.endsWith("/v1")) {
                resolvedBaseUrl = resolvedBaseUrl + "/";
            }
        }
        return resolvedBaseUrl;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static final class LocalHashEmbeddingModel implements EmbeddingModel {
        private final int dimensions;

        LocalHashEmbeddingModel(int dimensions) {
            this.dimensions = Math.max(64, dimensions);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            int i = 0;
            for (String text : request.getInstructions()) {
                embeddings.add(new Embedding(embed(text), i++));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(String text) {
            return hashEmbed(text);
        }

        @Override
        public float[] embed(Document document) {
            return hashEmbed(document == null ? "" : document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] hashEmbed(String text) {
            float[] vec = new float[dimensions];
            if (text == null || text.isBlank()) {
                return vec;
            }
            String normalized = text.toLowerCase().trim();
            String[] tokens = normalized.split("[\\s\\p{Punct}]+");
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                CRC32 crc = new CRC32();
                crc.update(token.getBytes(StandardCharsets.UTF_8));
                long h = crc.getValue();
                int idx = (int) (Math.floorMod(h, dimensions));
                int sign = ((h >> 1) & 1) == 0 ? 1 : -1;
                vec[idx] += sign;
                int idx2 = (int) (Math.floorMod(h / 7, dimensions));
                vec[idx2] += 0.5f * sign;
            }
            double sum = 0;
            for (float v : vec) sum += v * v;
            if (sum > 1e-12) {
                float inv = (float) (1.0 / Math.sqrt(sum));
                for (int i = 0; i < vec.length; i++) vec[i] *= inv;
            }
            return vec;
        }
    }
}
