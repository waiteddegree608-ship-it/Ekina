package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.UserMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for UserMemoryEntity.
 * Includes PGvector native queries for dynamic multi-tenant memory retrieval.
 */
@Repository
public interface UserMemoryRepository extends JpaRepository<UserMemoryEntity, UUID> {

    /**
     * Retrieve nearest memories for a specific user using PGvector cosine distance (<=>).
     *
     * @param userId       The target user ID (e.g. WeChat OpenID)
     * @param queryVector  The vector query as a String, e.g. "[0.1, 0.2, ...]"
     * @param limit        Max results to return
     * @return List of matching UserMemoryEntity records ordered by relevance
     */
    @Query(value = "SELECT id, user_id, content, embedding, created_at " +
                   "FROM user_memories " +
                   "WHERE user_id = :userId " +
                   "ORDER BY embedding <=> CAST(:queryVector AS vector) " +
                   "LIMIT :limit", 
           nativeQuery = true)
    List<UserMemoryEntity> findNearestMemories(@Param("userId") String userId,
                                               @Param("queryVector") String queryVector,
                                               @Param("limit") int limit);

    List<UserMemoryEntity> findByUserId(String userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "INSERT INTO user_memories (id, user_id, content, embedding, created_at) " +
                   "VALUES (:id, :userId, :content, CAST(:embedding AS vector), :createdAt) " +
                   "ON CONFLICT (id) DO UPDATE SET " +
                   "user_id = EXCLUDED.user_id, " +
                   "content = EXCLUDED.content, " +
                   "embedding = EXCLUDED.embedding, " +
                   "created_at = EXCLUDED.created_at", 
           nativeQuery = true)
    void insertOrUpdateMemory(@Param("id") UUID id,
                              @Param("userId") String userId,
                              @Param("content") String content,
                              @Param("embedding") String embedding,
                              @Param("createdAt") java.time.LocalDateTime createdAt);
}
