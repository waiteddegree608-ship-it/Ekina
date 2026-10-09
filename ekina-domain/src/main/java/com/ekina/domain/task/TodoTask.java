package com.ekina.domain.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Domain entity representing a schedule task or to-do item with alarms.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoTask {
    
    public enum TodoStatus {
        PENDING,     // Waiting to be triggered
        REMINDED,    // User has been alerted
        COMPLETED    // Task is finished
    }

    private Long id;
    private String userId;           // Foreign Key referencing User
    private String content;          // The task description (e.g. "Buy milk")
    private LocalDateTime targetTime; // The scheduled time for alarm/reminder
    private TodoStatus status;
    private LocalDateTime createdAt;
}
