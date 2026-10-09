package com.ekina.domain.rag;

import java.util.List;

/**
 * Domain interface defining a text Reranker.
 * Maps query and document candidate pairs to deep semantic scores using cross-attention models.
 */
public interface RerankService {

    /**
     * Re-scores document candidates based on query relevance.
     *
     * @param query     The original user query
     * @param documents Candidate text chunks
     * @param topN      Limit of top items to return
     * @return List of RerankResult holding document index and similarity score
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);
}
