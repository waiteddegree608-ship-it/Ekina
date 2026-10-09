package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.WorkflowStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for WorkflowStateEntity
 */
@Repository
public interface WorkflowStateRepositoryJPA extends JpaRepository<WorkflowStateEntity, String> {
}
