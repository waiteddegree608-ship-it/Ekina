package com.ekina.domain.repository;

/**
 * Domain interface for storing serialized Workflow engine states (byte arrays).
 * Implemented in infrastructure using database storage or cache.
 */
public interface WorkflowStateRepository {

    /**
     * Persists the serialized state bytes of a suspended workflow.
     */
    void saveState(String workflowId, byte[] stateBytes);

    /**
     * Retrieves the serialized state bytes of a suspended workflow.
     */
    byte[] getState(String workflowId);

    /**
     * Deletes/Cleans up the persisted state once a workflow resumes and finishes.
     */
    void deleteState(String workflowId);
}
