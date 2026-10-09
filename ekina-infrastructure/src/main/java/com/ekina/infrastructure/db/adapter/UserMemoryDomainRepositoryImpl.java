package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.repository.UserMemoryDomainRepository;
import com.ekina.domain.user.UserMemory;
import com.ekina.infrastructure.db.converter.VectorConverter;
import com.ekina.infrastructure.db.entity.UserMemoryEntity;
import com.ekina.infrastructure.db.repository.UserMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JPA Adapter implementing UserMemoryDomainRepository.
 * Handles float[] to String conversion for vector distance calculation in PostgreSQL.
 */
@Repository
public class UserMemoryDomainRepositoryImpl implements UserMemoryDomainRepository {

    @Autowired
    private UserMemoryRepository userMemoryRepository;

    private final VectorConverter vectorConverter = new VectorConverter();

    @Override
    public UserMemory save(UserMemory memory) {
        UUID id = memory.getId() != null ? memory.getId() : UUID.randomUUID();
        String queryVecStr = vectorConverter.convertToVectorString(memory.getEmbedding());
        java.time.LocalDateTime createdAt = memory.getCreatedAt() != null ? memory.getCreatedAt() : java.time.LocalDateTime.now();
        userMemoryRepository.insertOrUpdateMemory(id, memory.getUserId(), memory.getContent(), queryVecStr, createdAt);
        return UserMemory.builder()
                .id(id)
                .userId(memory.getUserId())
                .content(memory.getContent())
                .embedding(memory.getEmbedding())
                .createdAt(createdAt)
                .build();
    }

    @Override
    public void deleteById(UUID id) {
        userMemoryRepository.deleteById(id);
    }

    @Override
    public List<UserMemory> findNearest(String userId, float[] queryVector, int limit) {
        String queryVecStr = vectorConverter.convertToVectorString(queryVector);
        return userMemoryRepository.findNearestMemories(userId, queryVecStr, limit)
                .stream()
                .map(UserMemoryEntity::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserMemory> findAllByUserId(String userId) {
        return userMemoryRepository.findByUserId(userId)
                .stream()
                .map(UserMemoryEntity::toDomain)
                .collect(Collectors.toList());
    }
}
