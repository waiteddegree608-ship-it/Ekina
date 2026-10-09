package com.ekina.infrastructure.db.entity;

import com.ekina.domain.task.TodoTask;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "todo_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "target_time", nullable = false)
    private LocalDateTime targetTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TodoTask.TodoStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Map to domain TodoTask model.
     */
    public TodoTask toDomain() {
        return TodoTask.builder()
                .id(this.id)
                .userId(this.userId)
                .content(this.content)
                .targetTime(this.targetTime)
                .status(this.status)
                .createdAt(this.createdAt)
                .build();
    }

    /**
     * Build from domain TodoTask model.
     */
    public static TodoTaskEntity fromDomain(TodoTask task) {
        if (task == null) {
            return null;
        }
        return TodoTaskEntity.builder()
                .id(task.getId())
                .userId(task.getUserId())
                .content(task.getContent())
                .targetTime(task.getTargetTime())
                .status(task.getStatus() != null ? task.getStatus() : TodoTask.TodoStatus.PENDING)
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt() : LocalDateTime.now())
                .build();
    }
}
