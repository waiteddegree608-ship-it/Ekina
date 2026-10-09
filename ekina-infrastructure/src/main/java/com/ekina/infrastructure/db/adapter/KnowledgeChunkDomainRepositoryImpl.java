package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.rag.KnowledgeChunk;
import com.ekina.domain.repository.KnowledgeChunkDomainRepository;
import com.ekina.infrastructure.db.converter.VectorConverter;
import com.ekina.infrastructure.db.entity.KnowledgeChunkEntity;
import com.ekina.infrastructure.db.repository.KnowledgeChunkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JPA Adapter implementing KnowledgeChunkDomainRepository.
 */
@Repository
public class KnowledgeChunkDomainRepositoryImpl implements KnowledgeChunkDomainRepository {

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    private final VectorConverter vectorConverter = new VectorConverter();

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        UUID id = chunk.getId() != null ? chunk.getId() : UUID.randomUUID();
        String queryVecStr = vectorConverter.convertToVectorString(chunk.getEmbedding());
        java.time.LocalDateTime createdAt = chunk.getCreatedAt() != null ? chunk.getCreatedAt() : java.time.LocalDateTime.now();
        knowledgeChunkRepository.insertOrUpdateKnowledge(id, chunk.getDomainTag(), chunk.getTitle(), chunk.getContent(), queryVecStr, createdAt);
        return KnowledgeChunk.builder()
                .id(id)
                .domainTag(chunk.getDomainTag())
                .title(chunk.getTitle())
                .content(chunk.getContent())
                .embedding(chunk.getEmbedding())
                .createdAt(createdAt)
                .build();
    }

    @Override
    public void deleteById(UUID id) {
        knowledgeChunkRepository.deleteById(id);
    }

    @Override
    public List<KnowledgeChunk> findNearest(float[] queryVector, int limit) {
        String queryVecStr = vectorConverter.convertToVectorString(queryVector);
        return knowledgeChunkRepository.findNearestKnowledge(queryVecStr, limit)
                .stream()
                .map(KnowledgeChunkEntity::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeChunk> findNearestByDomains(float[] queryVector, List<String> domains, int limit) {
        String queryVecStr = vectorConverter.convertToVectorString(queryVector);
        return knowledgeChunkRepository.findNearestKnowledgeByDomains(queryVecStr, domains, limit)
                .stream()
                .map(KnowledgeChunkEntity::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeChunk> findAll() {
        return knowledgeChunkRepository.findAll()
                .stream()
                .map(KnowledgeChunkEntity::toDomain)
                .collect(Collectors.toList());
    }
}
