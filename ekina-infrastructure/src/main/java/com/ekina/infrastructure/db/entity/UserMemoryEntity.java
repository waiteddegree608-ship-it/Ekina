package com.ekina.infrastructure.db.entity;

import com.ekina.domain.user.UserMemory;
import com.ekina.infrastructure.db.converter.VectorConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_memories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemoryEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Convert(converter = VectorConverter.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Map to domain UserMemory model.
     */
    public UserMemory toDomain() {
        return UserMemory.builder()
                .id(this.id)
                .userId(this.userId)
                .content(this.content)
                .embedding(this.embedding)
                .createdAt(this.createdAt)
                .build();
    }

    /**
     * Build from domain UserMemory model.
     */
    public static UserMemoryEntity fromDomain(UserMemory memory) {
        if (memory == null) {
            return null;
        }
        return UserMemoryEntity.builder()
                .id(memory.getId() != null ? memory.getId() : UUID.randomUUID())
                .userId(memory.getUserId())
                .content(memory.getContent())
                .embedding(memory.getEmbedding())
                .createdAt(memory.getCreatedAt() != null ? memory.getCreatedAt() : LocalDateTime.now())
                .build();
    }
}
