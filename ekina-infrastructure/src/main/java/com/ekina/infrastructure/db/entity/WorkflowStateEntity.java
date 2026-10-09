package com.ekina.infrastructure.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA entity representing the binary snapshot state of a suspended workflow execution.
 */
@Entity
@Table(name = "workflow_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStateEntity {

    @Id
    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "state_bytes", columnDefinition = "BYTEA", nullable = false)
    private byte[] stateBytes;
}
