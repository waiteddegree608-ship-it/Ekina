package com.ekina.domain.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain entity representing a public, shared RAG knowledge base chunk.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {
    private UUID id;
    private String domainTag;   // Category classification (e.g. 'coding', 'finance') to prevent domain over-fitting
    private String title;       // Document title
    private String content;     // Text chunk content
    private float[] embedding;  // Vector embedding (e.g., 1024 dimensions)
    private LocalDateTime createdAt;
}
