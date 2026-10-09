package com.ekina.infrastructure.db.entity;

import com.ekina.domain.rag.KnowledgeChunk;
import com.ekina.infrastructure.db.converter.VectorConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "public_knowledge_chunks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "domain_tag", length = 64, nullable = false)
    private String domainTag;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Convert(converter = VectorConverter.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Map to domain KnowledgeChunk model.
     */
    public KnowledgeChunk toDomain() {
        return KnowledgeChunk.builder()
                .id(this.id)
                .domainTag(this.domainTag)
                .title(this.title)
                .content(this.content)
                .embedding(this.embedding)
                .createdAt(this.createdAt)
                .build();
    }

    /**
     * Build from domain KnowledgeChunk model.
     */
    public static KnowledgeChunkEntity fromDomain(KnowledgeChunk chunk) {
        if (chunk == null) {
            return null;
        }
        return KnowledgeChunkEntity.builder()
                .id(chunk.getId() != null ? chunk.getId() : UUID.randomUUID())
                .domainTag(chunk.getDomainTag())
                .title(chunk.getTitle())
                .content(chunk.getContent())
                .embedding(chunk.getEmbedding())
                .createdAt(chunk.getCreatedAt() != null ? chunk.getCreatedAt() : LocalDateTime.now())
                .build();
    }
}
