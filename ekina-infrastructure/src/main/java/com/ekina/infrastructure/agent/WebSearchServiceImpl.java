package com.ekina.infrastructure.agent;

import com.ekina.domain.agent.WebSearchService;
import com.ekina.infrastructure.agent.explore.ExploreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Web search: SearchAPI if key present, otherwise free DuckDuckGo / explore fallback (no fake weather).
 */
@Service
public class WebSearchServiceImpl implements WebSearchService {

    @Value("${ekina.tools.search-api-key:}")
    private String searchApiKey;

    @Autowired
    private ExploreService exploreService;

    private final RestClient restClient = RestClient.builder().build();

    @Override
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "empty query";
        }
        // Prefer free, real search first (always works offline of paid keys)
        String free = exploreService.webSearchFree(query);

        if (searchApiKey != null && !searchApiKey.isBlank() && !searchApiKey.contains("your-")) {
            try {
                String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String response = restClient.get()
                        .uri("https://www.searchapi.io/api/v1/search?engine=google&q=" + q + "&api_key=" + searchApiKey)
                        .retrieve()
                        .body(String.class);
                String paid = response != null && response.length() > 2500
                        ? response.substring(0, 2500) + "..."
                        : response;
                return free + "\n\n=== SearchAPI (paid) ===\n" + paid;
            } catch (Exception e) {
                return free + "\n\n(SearchAPI failed: " + e.getMessage() + ")";
            }
        }
        return free;
    }
}
