package com.ekina.application.rag;

import com.ekina.domain.agent.LlmService;
import com.ekina.domain.rag.KnowledgeChunk;
import com.ekina.domain.rag.RerankResult;
import com.ekina.domain.rag.RerankService;
import com.ekina.domain.repository.KnowledgeChunkDomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orchestrator for the advanced two-stage RAG retrieval pipeline.
 * Performs Query Expansion, Parallel Vector Searches, Reciprocal Rank Fusion (RRF),
 * and Cross-Encoder Reranking.
 */
@Service
public class RagOrchestrator {

    @Autowired
    private LlmService llmService;

    @Autowired
    private RerankService rerankService;

    @Autowired
    private KnowledgeChunkDomainRepository knowledgeChunkRepository;

    // Use Virtual Threads for parallel query vectorization and DB fetches
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final int RRF_K = 60; // Constant for Reciprocal Rank Fusion

    /**
     * Retrieve the most semantically relevant knowledge chunks for a user query.
     *
     * @param originalQuery The user's prompt/question
     * @param predictedDomains Domain tags to filter (null or empty to search globally)
     * @param targetCount   Final number of contexts to supply to the LLM
     * @return List of most relevant KnowledgeChunk elements
     */
    public List<KnowledgeChunk> retrieveContext(String originalQuery, List<String> predictedDomains, int targetCount) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Query Expansion: Generate 3同义问题 variations
        List<String> queries = expandQuery(originalQuery);
        queries.add(0, originalQuery); // Keep original query as first priority

        // 2. Parallel retrieval using Virtual Threads
        List<CompletableFuture<List<KnowledgeChunk>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> fetchNearest(q, predictedDomains), executor))
                .toList();

        // Wait for all fetches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<List<KnowledgeChunk>> retrievalLists = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 3. Merge results using Reciprocal Rank Fusion (RRF)
        List<KnowledgeChunk> mergedCandidates = fuseRankingsRRF(retrievalLists, 25);
        if (mergedCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. Second-stage: Cross-Encoder Reranking
        List<String> docContents = mergedCandidates.stream()
                .map(KnowledgeChunk::getContent)
                .collect(Collectors.toList());

        List<RerankResult> rerankResults = rerankService.rerank(originalQuery, docContents, targetCount);

        // Sort candidates based on Reranker score output
        List<KnowledgeChunk> finalSelection = new ArrayList<>();
        for (RerankResult res : rerankResults) {
            if (res.getIndex() >= 0 && res.getIndex() < mergedCandidates.size() && res.getScore() > 0.05) {
                finalSelection.add(mergedCandidates.get(res.getIndex()));
            }
        }

        // Fallback if rerank returns empty
        if (finalSelection.isEmpty()) {
            return mergedCandidates.subList(0, Math.min(mergedCandidates.size(), targetCount));
        }

        return finalSelection;
    }

    /**
     * Generates query variations using a fast model prompt.
     */
    private List<String> expandQuery(String query) {
        List<String> variations = new ArrayList<>();
        String prompt = "You are a query expansion component. Generate 3 short, distinct search query variations in Chinese for the following question to retrieve facts from a database:\n"
                + "\"" + query + "\"\n"
                + "Output ONLY the 3 queries separated by newlines, with no bullet points or numbering.";
        try {
            String output = llmService.chat(prompt);
            String[] lines = output.split("\n");
            for (String line : lines) {
                String clean = line.trim().replaceAll("^\\d+\\.\\s*", "").replaceAll("^-\\s*", "");
                if (!clean.isEmpty()) {
                    variations.add(clean);
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Query expansion failed: " + e.getMessage());
        }
        return variations.subList(0, Math.min(variations.size(), 3));
    }

    /**
     * Fetches top-15 vectors from db for a single query string.
     */
    private List<KnowledgeChunk> fetchNearest(String query, List<String> domains) {
        try {
            float[] vector = llmService.embed(query);
            if (domains != null && !domains.isEmpty()) {
                return knowledgeChunkRepository.findNearestByDomains(vector, domains, 15);
            } else {
                return knowledgeChunkRepository.findNearest(vector, 15);
            }
        } catch (Exception e) {
            System.err.println("Failed vector retrieval for query '" + query + "': " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Merges multiple ranked lists of KnowledgeChunks into a single list using Reciprocal Rank Fusion (RRF).
     */
    private List<KnowledgeChunk> fuseRankingsRRF(List<List<KnowledgeChunk>> lists, int topK) {
        Map<UUID, Double> rrfScores = new HashMap<>();
        Map<UUID, KnowledgeChunk> chunkLookup = new HashMap<>();

        for (List<KnowledgeChunk> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                KnowledgeChunk chunk = list.get(rank);
                UUID id = chunk.getId();
                chunkLookup.put(id, chunk);

                double scoreContribution = 1.0 / (RRF_K + (rank + 1));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + scoreContribution);
            }
        }

        // Sort items descending based on total RRF score
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> chunkLookup.get(entry.getKey()))
                .collect(Collectors.toList());
    }
}
