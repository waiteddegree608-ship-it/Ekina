package com.ekina.domain.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain entity representing an unstructured dynamic personal memory fragment.
 * Vectorized for semantic search using PGvector at the infrastructure layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMemory {
    private UUID id;
    private String userId;      // Foreign Key referencing User
    private String content;     // Memory text fragment (e.g. "User enjoys spicy food")
    private float[] embedding;  // 1024-dimensional semantic embedding vector
    private LocalDateTime createdAt;
}
