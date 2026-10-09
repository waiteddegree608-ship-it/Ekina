package com.ekina.bootstrap.controller;

import com.ekina.application.agent.AgentToolboxService;
import com.ekina.infrastructure.agent.mcp.McpClientManager;
import com.ekina.infrastructure.agent.mcp.McpProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private AgentToolboxService agentToolboxService;

    public static class AddServerRequest {
        private String name;
        private String type; // "stdio"
        private String command;
        private List<String> args;
        private String url;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class SkillResponse {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public SkillResponse(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getParameters() { return parameters; }
    }

    @GetMapping("/servers")
    public ResponseEntity<List<McpClientManager.McpServerStatus>> getServers() {
        return ResponseEntity.ok(mcpClientManager.getServersStatus());
    }

    @PostMapping("/servers")
    public ResponseEntity<?> addServer(@RequestBody AddServerRequest request) {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName(request.getName());
        config.setType(request.getType() != null ? request.getType() : "stdio");
        config.setCommand(request.getCommand());
        config.setArgs(request.getArgs() != null ? request.getArgs() : new ArrayList<>());
        config.setUrl(request.getUrl());

        boolean success = mcpClientManager.registerAndStartServer(config);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "MCP server started and registered"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", "Failed to start MCP server"));
        }
    }

    @DeleteMapping("/servers/{name}")
    public ResponseEntity<?> deleteServer(@PathVariable("name") String name) {
        mcpClientManager.deregisterAndStopServer(name);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "MCP server stopped and deregistered"));
    }

    private Map<String, Object> convertSchemaElement(dev.langchain4j.model.chat.request.json.JsonSchemaElement element) {
        if (element == null) {
            return null;
        }
        Map<String, Object> map = new java.util.HashMap<>();
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonStringSchema) {
            dev.langchain4j.model.chat.request.json.JsonStringSchema s = (dev.langchain4j.model.chat.request.json.JsonStringSchema) element;
            map.put("type", "string");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonIntegerSchema) {
            dev.langchain4j.model.chat.request.json.JsonIntegerSchema s = (dev.langchain4j.model.chat.request.json.JsonIntegerSchema) element;
            map.put("type", "integer");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonNumberSchema) {
            dev.langchain4j.model.chat.request.json.JsonNumberSchema s = (dev.langchain4j.model.chat.request.json.JsonNumberSchema) element;
            map.put("type", "number");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonBooleanSchema) {
            dev.langchain4j.model.chat.request.json.JsonBooleanSchema s = (dev.langchain4j.model.chat.request.json.JsonBooleanSchema) element;
            map.put("type", "boolean");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonEnumSchema) {
            dev.langchain4j.model.chat.request.json.JsonEnumSchema s = (dev.langchain4j.model.chat.request.json.JsonEnumSchema) element;
            map.put("type", "string");
            if (s.description() != null) map.put("description", s.description());
            if (s.enumValues() != null) map.put("enum", s.enumValues());
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonArraySchema) {
            dev.langchain4j.model.chat.request.json.JsonArraySchema s = (dev.langchain4j.model.chat.request.json.JsonArraySchema) element;
            map.put("type", "array");
            if (s.description() != null) map.put("description", s.description());
            if (s.items() != null) {
                map.put("items", convertSchemaElement(s.items()));
            }
        } else if (element instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema) {
            dev.langchain4j.model.chat.request.json.JsonObjectSchema s = (dev.langchain4j.model.chat.request.json.JsonObjectSchema) element;
            map.put("type", "object");
            if (s.description() != null) map.put("description", s.description());
            if (s.properties() != null) {
                Map<String, Object> props = new java.util.HashMap<>();
                for (Object key : s.properties().keySet()) {
                    dev.langchain4j.model.chat.request.json.JsonSchemaElement val = (dev.langchain4j.model.chat.request.json.JsonSchemaElement) s.properties().get(key);
                    props.put((String) key, convertSchemaElement(val));
                }
                map.put("properties", props);
            }
            if (s.required() != null) {
                map.put("required", s.required());
            }
        } else {
            map.put("type", "unknown");
        }
        return map;
    }

    @GetMapping("/skills")
    public ResponseEntity<List<SkillResponse>> getSkills() {
        List<ToolSpecification> specs = agentToolboxService.getToolSpecifications();
        List<SkillResponse> list = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            Map<String, Object> params = null;
            if (spec.parameters() != null) {
                params = convertSchemaElement(spec.parameters());
            }
            list.add(new SkillResponse(spec.name(), spec.description(), params));
        }
        return ResponseEntity.ok(list);
    }
}
