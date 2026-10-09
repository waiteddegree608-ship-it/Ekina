package com.ekina.infrastructure.agent.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible vision chat client used to describe video keyframes.
 */
@Component
public class VisionClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.siliconflow.cn}")
    private String baseUrl;

    private final VideoWatchProperties properties;

    public VisionClient(VideoWatchProperties properties) {
        this.properties = properties;
    }

    public String describeImageFile(java.io.File imageFile, String prompt) {
        if (imageFile == null || !imageFile.isFile()) {
            return "";
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("your-")) {
            return "[vision skipped: API key missing]";
        }
        try {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            // Keep request size reasonable
            if (bytes.length > 4_000_000) {
                return "[vision skipped: frame too large " + bytes.length + " bytes]";
            }
            String ext = imageFile.getName().toLowerCase(Locale.ROOT);
            String mime = "image/jpeg";
            if (ext.endsWith(".png")) {
                mime = "image/png";
            } else if (ext.endsWith(".webp")) {
                mime = "image/webp";
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return describeBase64(b64, mime, prompt);
        } catch (Exception e) {
            return "[vision error: " + e.getMessage() + "]";
        }
    }

    public String describeBase64(String base64Data, String mimeType, String prompt) throws Exception {
        String resolvedBaseUrl = baseUrl == null ? "https://api.siliconflow.cn" : baseUrl.trim();
        if (!resolvedBaseUrl.endsWith("/v1") && !resolvedBaseUrl.endsWith("/v1/")) {
            resolvedBaseUrl = resolvedBaseUrl.endsWith("/") ? resolvedBaseUrl + "v1" : resolvedBaseUrl + "/v1";
        }
        String url = resolvedBaseUrl.endsWith("/")
                ? resolvedBaseUrl + "chat/completions"
                : resolvedBaseUrl + "/chat/completions";

        String textPrompt = (prompt == null || prompt.isBlank())
                ? "请用中文简洁描述这个视频关键帧：画面主体、文字/字幕、图表、人物动作与场景。不要编造看不清的细节。"
                : prompt;

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.getVisionModel());
        payload.put("max_tokens", Math.min(400, Math.max(80, properties.getMaxVisionCharsPerFrame())));
        payload.put("temperature", 0.2);

        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        textContent.put("text", textPrompt);

        Map<String, Object> imageContent = new HashMap<>();
        imageContent.put("type", "image_url");
        Map<String, String> imageUrl = new HashMap<>();
        imageUrl.put("url", "data:" + mimeType + ";base64," + base64Data);
        imageContent.put("image_url", imageUrl);

        List<Object> contentList = new ArrayList<>();
        contentList.add(textContent);
        contentList.add(imageContent);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", contentList);

        payload.put("messages", List.of(userMsg));

        String body = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return "[vision http " + response.statusCode() + ": " + trim(response.body(), 300) + "]";
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "[vision empty response]";
        }
        String content = choices.get(0).path("message").path("content").asText("");
        return trim(content, properties.getMaxVisionCharsPerFrame() * 2);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "...";
    }
}
