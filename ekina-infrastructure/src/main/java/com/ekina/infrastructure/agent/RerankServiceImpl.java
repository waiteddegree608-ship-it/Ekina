package com.ekina.infrastructure.agent;

import com.ekina.domain.rag.RerankResult;
import com.ekina.domain.rag.RerankService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure implementation of RerankService calling SiliconFlow Rerank endpoint.
 * Utilizes the new Spring 6/Spring Boot 3 RestClient.
 */
@Service
public class RerankServiceImpl implements RerankService {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.siliconflow.cn/v1")
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        List<RerankResult> results = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return results;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "BAAI/bge-reranker-large");
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", topN);

        try {
            String response = restClient.post()
                    .uri("/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (root.has("results") && root.get("results").isArray()) {
                for (JsonNode resNode : root.get("results")) {
                    int index = resNode.get("index").asInt();
                    double score = resNode.get("relevance_score").asDouble();
                    results.add(new RerankResult(index, score));
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to call SiliconFlow Rerank API: " + e.getMessage());
            // Fallback: return candidates sequentially with dummy score of 1.0 to prevent blocking the query
            for (int i = 0; i < Math.min(documents.size(), topN); i++) {
                results.add(new RerankResult(i, 1.0));
            }
        }
        return results;
    }
}
