package com.ekina.application.workflow;

import com.ekina.application.agent.AgentToolboxService;
import com.ekina.common.serialization.KryoSerializer;
import com.ekina.domain.repository.WorkflowStateRepository;
import com.ekina.domain.workflow.WorkflowContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance Workflow Execution Engine.
 * Resolves workflow DAG topology, executes nodes using Java 21 Virtual Threads,
 * and handles human-in-the-loop approval suspensions via Kryo serialization.
 */
@Service
public class WorkflowEngine {

    @Autowired
    private WorkflowStateRepository stateRepository;

    @Autowired
    private AgentToolboxService agentToolboxService;

    // Use Virtual Threads for concurrent workflow run execution
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public static class WorkflowNode implements Serializable {
        private String id;
        private String name;
        private String type; // "TOOL", "APPROVAL"
        private String toolName;
        private Map<String, Object> arguments;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
    }

    /**
     * Spawns a new workflow execution context and triggers execution.
     *
     * @param userId The user starting the workflow
     * @param dsl    JSON/DSL representation of the workflow nodes and parameters
     * @return Unique Workflow ID to track execution progress
     */
    public String startWorkflow(String userId, String dsl) {
        String workflowId = UUID.randomUUID().toString();

        List<WorkflowNode> nodes = parseDsl(dsl);
        String startNodeId = (nodes != null && !nodes.isEmpty()) ? nodes.get(0).getId() : "start";

        WorkflowContext context = WorkflowContext.builder()
                .workflowId(workflowId)
                .userId(userId)
                .currentState("RUNNING")
                .currentRunningNodeId(startNodeId)
                .build();

        // Feed parameters into scope
        context.getVariables().put("dsl", dsl);
        context.getVariables().put("startTime", java.time.LocalDateTime.now().toString());

        // Submit execution asynchronously in a Virtual Thread
        executor.submit(() -> executeWorkflow(context));

        return workflowId;
    }

    /**
     * Resumes a suspended workflow upon receiving user approval or rejection.
     *
     * @param workflowId The target workflow ID
     * @param approved   True if approved, false if rejected
     */
    public void resumeWorkflow(String workflowId, boolean approved) {
        byte[] stateBytes = stateRepository.getState(workflowId);
        if (stateBytes == null || stateBytes.length == 0) {
            System.err.println("Warning: Suspended state for workflow " + workflowId + " not found.");
            return;
        }

        // Deserialize context graph via Kryo
        WorkflowContext context = KryoSerializer.deserialize(stateBytes, WorkflowContext.class);
        if (context == null) {
            System.err.println("Failed to deserialize workflow context for workflow " + workflowId);
            return;
        }

        String dsl = (String) context.getVariables().get("dsl");
        List<WorkflowNode> nodes = parseDsl(dsl);
        String suspendedNodeId = context.getCurrentRunningNodeId();

        if (approved) {
            System.out.println("Workflow " + workflowId + " APPROVED at node " + suspendedNodeId + ". Resuming...");
            context.setCurrentState("RUNNING");
            context.getCompletedNodeIds().add(suspendedNodeId);

            // Find next node
            WorkflowNode nextNode = findNextNode(nodes, suspendedNodeId);
            if (nextNode != null) {
                context.setCurrentRunningNodeId(nextNode.getId());
            } else {
                context.setCurrentState("COMPLETED");
            }

            // Resume execution in a Virtual Thread
            executor.submit(() -> executeWorkflow(context));
        } else {
            System.out.println("Workflow " + workflowId + " REJECTED. Terminating...");
            context.setCurrentState("FAILED");
            stateRepository.deleteState(workflowId); // Clean up database state
        }
    }

    /**
     * Internal execution loop running inside Virtual Threads.
     */
    private void executeWorkflow(WorkflowContext context) {
        String workflowId = context.getWorkflowId();
        String dsl = (String) context.getVariables().get("dsl");
        List<WorkflowNode> nodes = parseDsl(dsl);

        if (nodes == null || nodes.isEmpty()) {
            System.err.println("[Workflow Engine] DSL is empty or invalid for workflow: " + workflowId);
            context.setCurrentState("FAILED");
            return;
        }

        while ("RUNNING".equals(context.getCurrentState())) {
            String nodeId = context.getCurrentRunningNodeId();
            WorkflowNode node = findNodeById(nodes, nodeId);

            if (node == null) {
                System.err.println("[Workflow Engine] Node not found: " + nodeId);
                context.setCurrentState("FAILED");
                break;
            }

            System.out.println("[Workflow Engine] Executing node: " + node.getName() + " (" + node.getId() + ")");

            if ("TOOL".equalsIgnoreCase(node.getType())) {
                try {
                    Map<String, Object> resolvedArgs = resolveArguments(node.getArguments(), context.getVariables());
                    String result = agentToolboxService.executeTool(node.getToolName(), resolvedArgs);

                    context.getVariables().put(node.getId() + ".output", result);
                    context.getCompletedNodeIds().add(node.getId());

                    WorkflowNode nextNode = findNextNode(nodes, node.getId());
                    if (nextNode != null) {
                        context.setCurrentRunningNodeId(nextNode.getId());
                    } else {
                        context.setCurrentState("COMPLETED");
                    }
                } catch (Exception e) {
                    System.err.println("[Workflow Engine] Node " + node.getId() + " failed: " + e.getMessage());
                    context.getVariables().put(node.getId() + ".error", e.getMessage());
                    context.setCurrentState("FAILED");
                }
            } 
            else if ("APPROVAL".equalsIgnoreCase(node.getType())) {
                System.out.println("[Workflow Engine] Reached approval node " + node.getId() + ": Pausing execution thread.");
                context.setCurrentState("SUSPENDED");

                // Serialize context graph using Kryo
                byte[] stateBytes = KryoSerializer.serialize(context);

                // Save context snapshot to Database
                stateRepository.saveState(workflowId, stateBytes);
                System.out.println("[Workflow Engine] State serialized and saved to DB. Workflow ID: " + workflowId);
                return; // Halt this thread execution
            } 
            else {
                // Fallback for custom or unknown nodes
                context.getCompletedNodeIds().add(node.getId());
                WorkflowNode nextNode = findNextNode(nodes, node.getId());
                if (nextNode != null) {
                    context.setCurrentRunningNodeId(nextNode.getId());
                } else {
                    context.setCurrentState("COMPLETED");
                }
            }
        }

        if ("COMPLETED".equals(context.getCurrentState()) || "FAILED".equals(context.getCurrentState())) {
            stateRepository.deleteState(workflowId);
            System.out.println("[Workflow Engine] Workflow finished with state " + context.getCurrentState() + ". Cleaned up DB state.");
        }
    }

    private List<WorkflowNode> parseDsl(String dsl) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(dsl, new com.fasterxml.jackson.core.type.TypeReference<List<WorkflowNode>>() {});
        } catch (Exception e) {
            System.err.println("Failed to parse workflow DSL: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private WorkflowNode findNodeById(List<WorkflowNode> nodes, String id) {
        for (WorkflowNode node : nodes) {
            if (node.getId().equals(id)) {
                return node;
            }
        }
        return null;
    }

    private WorkflowNode findNextNode(List<WorkflowNode> nodes, String currentId) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getId().equals(currentId)) {
                if (i + 1 < nodes.size()) {
                    return nodes.get(i + 1);
                }
                break;
            }
        }
        return null;
    }

    private Map<String, Object> resolveArguments(Map<String, Object> args, Map<String, Object> variables) {
        if (args == null) return new java.util.HashMap<>();
        Map<String, Object> resolved = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), variables));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("${") && str.endsWith("}")) {
                String key = str.substring(2, str.length() - 1);
                if (variables.containsKey(key)) {
                    return variables.get(key);
                }
            }
            for (String key : variables.keySet()) {
                String placeholder = "${" + key + "}";
                if (str.contains(placeholder)) {
                    Object val = variables.get(key);
                    str = str.replace(placeholder, val != null ? val.toString() : "");
                }
            }
            return str;
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> resolvedMap = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                resolvedMap.put(entry.getKey(), resolveValue(entry.getValue(), variables));
            }
            return resolvedMap;
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            List<Object> resolvedList = new ArrayList<>();
            for (Object item : list) {
                resolvedList.add(resolveValue(item, variables));
            }
            return resolvedList;
        }
        return value;
    }
}
