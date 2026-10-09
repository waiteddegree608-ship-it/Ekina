package com.ekina.domain.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain object representing the score and index returned from the Reranking engine.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RerankResult {
    private int index;
    private double score;
}
