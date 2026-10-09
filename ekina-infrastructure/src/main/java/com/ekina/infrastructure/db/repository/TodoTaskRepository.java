package com.ekina.infrastructure.db.repository;

import com.ekina.domain.task.TodoTask;
import com.ekina.infrastructure.db.entity.TodoTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA Repository for TodoTaskEntity.
 * Includes helper methods for finding user schedules and tasks due for trigger.
 */
@Repository
public interface TodoTaskRepository extends JpaRepository<TodoTaskEntity, Long> {

    /**
     * Find todo tasks for a user filtered by status.
     */
    List<TodoTaskEntity> findByUserIdAndStatus(String userId, TodoTask.TodoStatus status);

    /**
     * Retrieves all pending tasks that have reached or passed their target alarm time.
     */
    @Query("SELECT t FROM TodoTaskEntity t WHERE t.status = 'PENDING' AND t.targetTime <= :now")
    List<TodoTaskEntity> findDueTasks(@Param("now") LocalDateTime now);
}
