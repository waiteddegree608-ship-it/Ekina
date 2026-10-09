package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.KnowledgeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for KnowledgeChunkEntity.
 * Includes PGvector vector search methods and domain metadata filtering.
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, UUID> {

    /**
     * Retrieve nearest shared knowledge chunks across the entire database using PGvector cosine distance (<=>).
     *
     * @param queryVector The query vector string representation, e.g. "[0.1, 0.2, ...]"
     * @param limit       Max results
     * @return List of matching KnowledgeChunkEntity records
     */
    @Query(value = "SELECT id, domain_tag, title, content, embedding, created_at " +
                   "FROM public_knowledge_chunks " +
                   "ORDER BY embedding <=> CAST(:queryVector AS vector) " +
                   "LIMIT :limit", 
           nativeQuery = true)
    List<KnowledgeChunkEntity> findNearestKnowledge(@Param("queryVector") String queryVector,
                                                    @Param("limit") int limit);

    /**
     * Retrieve nearest knowledge chunks filtered by domain tags to prevent irrelevant domain domination.
     *
     * @param queryVector The query vector string representation
     * @param domains     List of domain categories to query (e.g. 'coding', 'general')
     * @param limit       Max results
     * @return List of matching KnowledgeChunkEntity records
     */
    @Query(value = "SELECT id, domain_tag, title, content, embedding, created_at " +
                   "FROM public_knowledge_chunks " +
                   "WHERE domain_tag IN (:domains) " +
                   "ORDER BY embedding <=> CAST(:queryVector AS vector) " +
                   "LIMIT :limit", 
           nativeQuery = true)
    List<KnowledgeChunkEntity> findNearestKnowledgeByDomains(@Param("queryVector") String queryVector,
                                                             @Param("domains") List<String> domains,
                                                             @Param("limit") int limit);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "INSERT INTO public_knowledge_chunks (id, domain_tag, title, content, embedding, created_at) " +
                   "VALUES (:id, :domainTag, :title, :content, CAST(:embedding AS vector), :createdAt) " +
                   "ON CONFLICT (id) DO UPDATE SET " +
                   "domain_tag = EXCLUDED.domain_tag, " +
                   "title = EXCLUDED.title, " +
                   "content = EXCLUDED.content, " +
                   "embedding = EXCLUDED.embedding, " +
                   "created_at = EXCLUDED.created_at", 
           nativeQuery = true)
    void insertOrUpdateKnowledge(@Param("id") UUID id,
                                 @Param("domainTag") String domainTag,
                                 @Param("title") String title,
                                 @Param("content") String content,
                                 @Param("embedding") String embedding,
                                 @Param("createdAt") java.time.LocalDateTime createdAt);
}
