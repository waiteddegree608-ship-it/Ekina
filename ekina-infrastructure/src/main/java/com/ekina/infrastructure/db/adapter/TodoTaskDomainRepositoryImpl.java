package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.repository.TodoTaskDomainRepository;
import com.ekina.domain.task.TodoTask;
import com.ekina.infrastructure.db.entity.TodoTaskEntity;
import com.ekina.infrastructure.db.repository.TodoTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA Adapter implementing TodoTaskDomainRepository.
 */
@Repository
public class TodoTaskDomainRepositoryImpl implements TodoTaskDomainRepository {

    @Autowired
    private TodoTaskRepository todoTaskRepository;

    @Override
    public TodoTask save(TodoTask task) {
        TodoTaskEntity entity = TodoTaskEntity.fromDomain(task);
        return todoTaskRepository.save(entity).toDomain();
    }

    @Override
    public Optional<TodoTask> findById(Long id) {
        return todoTaskRepository.findById(id).map(TodoTaskEntity::toDomain);
    }

    @Override
    public List<TodoTask> findByUserIdAndStatus(String userId, TodoTask.TodoStatus status) {
        return todoTaskRepository.findByUserIdAndStatus(userId, status)
                .stream()
                .map(TodoTaskEntity::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<TodoTask> findDueTasks(LocalDateTime now) {
        return todoTaskRepository.findDueTasks(now)
                .stream()
                .map(TodoTaskEntity::toDomain)
                .collect(Collectors.toList());
    }
}
