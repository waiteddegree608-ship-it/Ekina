package com.ekina.infrastructure.agent;

import com.ekina.domain.task.TtsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Infrastructure implementation of TtsService calling SiliconFlow TTS endpoint.
 */
@Service
public class TtsServiceImpl implements TtsService {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.siliconflow.cn/v1")
            .build();

    @Override
    public byte[] textToSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new byte[0];
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "fishaudio/fish-speech-1.4"); // Standard TTS model on SiliconFlow
        requestBody.put("input", text);
        requestBody.put("voice", "alex"); // Voice persona
        requestBody.put("response_format", "mp3");

        try {
            return restClient.post()
                    .uri("/audio/speech")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            System.err.println("Warning: Failed to call SiliconFlow TTS: " + e.getMessage());
            return new byte[0];
        }
    }
}
