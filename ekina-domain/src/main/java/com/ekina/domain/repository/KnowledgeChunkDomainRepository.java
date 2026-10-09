package com.ekina.domain.repository;

import com.ekina.domain.rag.KnowledgeChunk;
import java.util.List;
import java.util.UUID;

public interface KnowledgeChunkDomainRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);
    void deleteById(UUID id);
    List<KnowledgeChunk> findNearest(float[] queryVector, int limit);
    List<KnowledgeChunk> findNearestByDomains(float[] queryVector, List<String> domains, int limit);
    List<KnowledgeChunk> findAll();
}
