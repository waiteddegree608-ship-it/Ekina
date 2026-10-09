package com.ekina.bootstrap.controller;

import com.ekina.application.agent.AgentToolboxService;
import com.ekina.application.workflow.WorkflowEngine;
import com.ekina.common.serialization.KryoSerializer;
import com.ekina.domain.repository.WorkflowStateRepository;
import com.ekina.domain.workflow.WorkflowContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private WorkflowStateRepository stateRepository;

    @Autowired
    private AgentToolboxService agentToolboxService;

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    public static class StartRequest {
        private String userId;
        private String dsl;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getDsl() {
            return dsl;
        }

        public void setDsl(String dsl) {
            this.dsl = dsl;
        }
    }

    public static class StartResponse {
        private String workflowId;

        public StartResponse(String workflowId) {
            this.workflowId = workflowId;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }
    }

    public static class ApproveRequest {
        private boolean approved;

        public boolean isApproved() {
            return approved;
        }

        public void setApproved(boolean approved) {
            this.approved = approved;
        }
    }

    public static class ProposeRequest {
        private String prompt;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class StatusResponse {
        private String workflowId;
        private String currentState;
        private String currentRunningNodeId;
        private Map<String, Object> variables;

        public StatusResponse(String workflowId, String currentState, String currentRunningNodeId, Map<String, Object> variables) {
            this.workflowId = workflowId;
            this.currentState = currentState;
            this.currentRunningNodeId = currentRunningNodeId;
            this.variables = variables;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        public String getCurrentState() {
            return currentState;
        }

        public String getCurrentRunningNodeId() {
            return currentRunningNodeId;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }
    }

    @PostMapping("/start")
    public StartResponse startWorkflow(@RequestBody StartRequest request) {
        String runId = workflowEngine.startWorkflow(request.getUserId(), request.getDsl());
        return new StartResponse(runId);
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Map<String, String>> approveWorkflow(@PathVariable("runId") String runId, @RequestBody ApproveRequest request) {
        workflowEngine.resumeWorkflow(runId, request.isApproved());
        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }

    @GetMapping("/{runId}/status")
    public ResponseEntity<?> getStatus(@PathVariable("runId") String runId) {
        byte[] bytes = stateRepository.getState(runId);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.ok(Map.of("workflowId", runId, "currentState", "COMPLETED_OR_NOT_FOUND"));
        }
        WorkflowContext context = KryoSerializer.deserialize(bytes, WorkflowContext.class);
        if (context == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to deserialize state"));
        }
        return ResponseEntity.ok(new StatusResponse(
                context.getWorkflowId(),
                context.getCurrentState(),
                context.getCurrentRunningNodeId(),
                context.getVariables()
        ));
    }

    @PostMapping("/propose")
    public ResponseEntity<?> proposeWorkflow(@RequestBody ProposeRequest request) {
        List<ToolSpecification> specs = agentToolboxService.getToolSpecifications();
        StringBuilder toolsBuilder = new StringBuilder();
        for (ToolSpecification spec : specs) {
            toolsBuilder.append("- Tool Name: ").append(spec.name()).append("\n");
            toolsBuilder.append("  Description: ").append(spec.description()).append("\n");
            if (spec.parameters() != null) {
                toolsBuilder.append("  Parameters: ").append(spec.parameters().properties().keySet()).append("\n");
            }
            toolsBuilder.append("\n");
        }

        String systemPrompt = "You are a Workflow DSL generator. You translate user requests into a JSON array of workflow steps.\n"
                + "Each step must be an object with the following fields:\n"
                + "- id: Unique string like \"node-1\", \"node-2\", etc.\n"
                + "- name: Clear summary of what this step does.\n"
                + "- type: Either \"TOOL\" (to execute a tool) or \"APPROVAL\" (to wait for human approval).\n"
                + "- toolName: The exact name of the tool to execute (only for TOOL type).\n"
                + "- arguments: A key-value map of arguments. You can reference previous steps outputs using \"${node-id.output}\".\n\n"
                + "Available tools:\n" + toolsBuilder.toString() + "\n"
                + "Output ONLY the raw JSON array. Do not include markdown formatting or ```json code blocks. Just output valid JSON.";

        dev.langchain4j.data.message.SystemMessage sysMsg = new dev.langchain4j.data.message.SystemMessage(systemPrompt);
        dev.langchain4j.data.message.UserMessage userMsg = new dev.langchain4j.data.message.UserMessage(request.getPrompt());

        Response<AiMessage> response = chatLanguageModel.generate(List.of(sysMsg, userMsg));
        String rawJson = response.content().text();

        rawJson = rawJson.trim();
        if (rawJson.startsWith("```")) {
            rawJson = rawJson.substring(rawJson.indexOf("\n") + 1);
        }
        if (rawJson.endsWith("```")) {
            rawJson = rawJson.substring(0, rawJson.lastIndexOf("```"));
        }
        rawJson = rawJson.trim();

        return ResponseEntity.ok(Map.of("dsl", rawJson));
    }
}
