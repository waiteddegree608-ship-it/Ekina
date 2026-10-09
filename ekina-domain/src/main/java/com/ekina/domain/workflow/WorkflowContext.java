package com.ekina.domain.workflow;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Domain entity representing the execution context of a Workflow.
 * Fully serializable using Kryo to support execution suspension (approval wait) and recovery.
 */
public class WorkflowContext implements Serializable {
    
    private String workflowId;
    private String userId;
    private String currentState;          // "RUNNING", "SUSPENDED", "COMPLETED", "FAILED"
    private String currentRunningNodeId;
    private Set<String> completedNodeIds = new HashSet<>();
    private Map<String, Object> variables = new HashMap<>(); // Holds intermediate execution parameters

    public WorkflowContext() {}

    public WorkflowContext(String workflowId, String userId, String currentState, String currentRunningNodeId, Set<String> completedNodeIds, Map<String, Object> variables) {
        this.workflowId = workflowId;
        this.userId = userId;
        this.currentState = currentState;
        this.currentRunningNodeId = currentRunningNodeId;
        this.completedNodeIds = completedNodeIds != null ? completedNodeIds : new HashSet<>();
        this.variables = variables != null ? variables : new HashMap<>();
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public String getCurrentRunningNodeId() {
        return currentRunningNodeId;
    }

    public void setCurrentRunningNodeId(String currentRunningNodeId) {
        this.currentRunningNodeId = currentRunningNodeId;
    }

    public Set<String> getCompletedNodeIds() {
        return completedNodeIds;
    }

    public void setCompletedNodeIds(Set<String> completedNodeIds) {
        this.completedNodeIds = completedNodeIds;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    public static WorkflowContextBuilder builder() {
        return new WorkflowContextBuilder();
    }

    public static class WorkflowContextBuilder {
        private String workflowId;
        private String userId;
        private String currentState;
        private String currentRunningNodeId;
        private Set<String> completedNodeIds = new HashSet<>();
        private Map<String, Object> variables = new HashMap<>();

        public WorkflowContextBuilder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public WorkflowContextBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public WorkflowContextBuilder currentState(String currentState) {
            this.currentState = currentState;
            return this;
        }

        public WorkflowContextBuilder currentRunningNodeId(String currentRunningNodeId) {
            this.currentRunningNodeId = currentRunningNodeId;
            return this;
        }

        public WorkflowContextBuilder completedNodeIds(Set<String> completedNodeIds) {
            this.completedNodeIds = completedNodeIds;
            return this;
        }

        public WorkflowContextBuilder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public WorkflowContext build() {
            return new WorkflowContext(workflowId, userId, currentState, currentRunningNodeId, completedNodeIds, variables);
        }
    }
}
