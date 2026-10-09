package com.ekina.domain.repository;

import com.ekina.domain.task.TodoTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TodoTaskDomainRepository {
    TodoTask save(TodoTask task);
    Optional<TodoTask> findById(Long id);
    List<TodoTask> findByUserIdAndStatus(String userId, TodoTask.TodoStatus status);
    List<TodoTask> findDueTasks(LocalDateTime now);
}
