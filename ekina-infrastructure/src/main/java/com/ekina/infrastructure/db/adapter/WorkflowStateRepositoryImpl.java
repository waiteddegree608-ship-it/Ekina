package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.repository.WorkflowStateRepository;
import com.ekina.infrastructure.db.entity.WorkflowStateEntity;
import com.ekina.infrastructure.db.repository.WorkflowStateRepositoryJPA;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA Adapter implementing WorkflowStateRepository.
 */
@Repository
public class WorkflowStateRepositoryImpl implements WorkflowStateRepository {


    @Autowired
    private WorkflowStateRepositoryJPA jpaRepository;

    @Override
    public void saveState(String workflowId, byte[] stateBytes) {
        WorkflowStateEntity entity = WorkflowStateEntity.builder()
                .workflowId(workflowId)
                .stateBytes(stateBytes)
                .build();
        jpaRepository.save(entity);
    }

    @Override
    public byte[] getState(String workflowId) {
        Optional<WorkflowStateEntity> entityOpt = jpaRepository.findById(workflowId);
        return entityOpt.map(WorkflowStateEntity::getStateBytes).orElse(null);
    }

    @Override
    public void deleteState(String workflowId) {
        jpaRepository.deleteById(workflowId);
    }
}
