package com.ekina.infrastructure.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.mcp.spec.McpSchema;
import org.springframework.ai.mcp.client.McpClient;
import org.springframework.ai.mcp.client.McpSyncClient;
import org.springframework.ai.mcp.client.transport.ServerParameters;
import org.springframework.ai.mcp.client.transport.StdioClientTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing connections to multiple external Model Context Protocol (MCP) servers.
 * Launches Stdio subprocesses dynamically and maps MCP tools to LangChain4j ToolSpecifications.
 */
@Service
public class McpClientManager {

    @Autowired
    private McpProperties mcpProperties;

    @org.springframework.beans.factory.annotation.Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpProperties.ServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpSyncClient> serverClients = new ConcurrentHashMap<>();
    private final Map<String, String> serverStatus = new ConcurrentHashMap<>();
    private final Map<String, List<String>> serverTools = new ConcurrentHashMap<>();
    private final Map<String, McpSyncClient> toolToClientMap = new ConcurrentHashMap<>();

    public static class McpServerStatus {
        private String name;
        private String type;
        private String command;
        private List<String> args;
        private String url;
        private String status;
        private List<String> tools;

        public McpServerStatus() {}

        public McpServerStatus(String name, String type, String command, List<String> args, String url, String status, List<String> tools) {
            this.name = name;
            this.type = type;
            this.command = command;
            this.args = args;
            this.url = url;
            this.status = status;
            this.tools = tools;
        }

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
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
    }

    @PostConstruct
    public void init() {
        // 1. Load and start static servers
        if (mcpProperties.getServers() != null) {
            for (McpProperties.ServerConfig config : mcpProperties.getServers()) {
                serverConfigs.put(config.getName(), config);
                startServer(config);
            }
        }
        // 2. Load and start dynamic servers
        loadDynamicServers();
        for (Map.Entry<String, McpProperties.ServerConfig> entry : serverConfigs.entrySet()) {
            if (!isStaticServer(entry.getKey())) {
                startServer(entry.getValue());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, McpSyncClient> entry : serverClients.entrySet()) {
            try {
                System.out.println("[MCP Manager] Stopping MCP Server: " + entry.getKey());
                entry.getValue().close();
            } catch (Exception e) {
                System.err.println("[MCP Manager] Error closing client: " + e.getMessage());
            }
        }
    }

    public synchronized boolean registerAndStartServer(McpProperties.ServerConfig config) {
        boolean started = startServer(config);
        if (started) {
            saveDynamicServers();
        }
        return started;
    }

    public synchronized void deregisterAndStopServer(String name) {
        stopServer(name);
        saveDynamicServers();
    }

    public List<McpServerStatus> getServersStatus() {
        List<McpServerStatus> list = new ArrayList<>();
        for (String name : serverConfigs.keySet()) {
            McpProperties.ServerConfig config = serverConfigs.get(name);
            List<String> tools = serverTools.getOrDefault(name, new ArrayList<>());
            list.add(new McpServerStatus(
                    config.getName(),
                    config.getType(),
                    config.getCommand(),
                    config.getArgs(),
                    config.getUrl(),
                    serverStatus.getOrDefault(name, "FAILED"),
                    tools
            ));
        }
        return list;
    }

    private synchronized boolean startServer(McpProperties.ServerConfig config) {
        try {
            System.out.println("[MCP Manager] Initializing MCP Server: " + config.getName() + " of type " + config.getType());
            if ("stdio".equalsIgnoreCase(config.getType())) {
                List<String> commandArgs = new ArrayList<>();
                String mainCommand = config.getCommand();

                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win") && "npx".equalsIgnoreCase(mainCommand)) {
                    mainCommand = "cmd.exe";
                    commandArgs.add("/c");
                    commandArgs.add("npx");
                }

                if (config.getArgs() != null) {
                    commandArgs.addAll(config.getArgs());
                }

                ServerParameters params = ServerParameters.builder(mainCommand)
                        .args(commandArgs)
                        .build();

                StdioClientTransport transport = new StdioClientTransport(params);

                // Patch StdioClientTransport's objectMapper to ignore unknown fields
                try {
                    java.lang.reflect.Field f = StdioClientTransport.class.getDeclaredField("objectMapper");
                    f.setAccessible(true);
                    ObjectMapper mapper = (ObjectMapper) f.get(transport);
                    mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                } catch (Exception e) {
                    System.err.println("[MCP Manager] Failed to patch StdioClientTransport objectMapper: " + e.getMessage());
                }

                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(15))
                        .build();

                client.initialize();
                serverClients.put(config.getName(), client);
                serverStatus.put(config.getName(), "CONNECTED");

                // Retrieve initial tools
                List<String> tools = new ArrayList<>();
                McpSchema.ListToolsResult listToolsResult = client.listTools();
                if (listToolsResult != null && listToolsResult.tools() != null) {
                    for (McpSchema.Tool mcpTool : listToolsResult.tools()) {
                        toolToClientMap.put(mcpTool.name(), client);
                        tools.add(mcpTool.name());
                    }
                }
                serverTools.put(config.getName(), tools);

                System.out.println("[MCP Manager] Successfully started MCP Server: " + config.getName());
                return true;
            } else {
                serverStatus.put(config.getName(), "FAILED: Unsupported type " + config.getType());
                return false;
            }
        } catch (Exception e) {
            System.err.println("[MCP Manager] Failed to start MCP Server " + config.getName() + ": " + e.getMessage());
            serverStatus.put(config.getName(), "FAILED: " + e.getMessage());
            return false;
        }
    }

    private synchronized void stopServer(String name) {
        McpSyncClient client = serverClients.remove(name);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                System.err.println("[MCP Manager] Error closing client " + name + ": " + e.getMessage());
            }
        }
        serverStatus.remove(name);
        List<String> tools = serverTools.remove(name);
        if (tools != null) {
            for (String tool : tools) {
                toolToClientMap.remove(tool);
            }
        }
        serverConfigs.remove(name);
    }

    private void loadDynamicServers() {
        File file = new File(sandboxDir, "dynamic-mcp.json");
        if (!file.exists()) {
            return;
        }
        try {
            List<McpProperties.ServerConfig> configs = objectMapper.readValue(file,
                    new com.fasterxml.jackson.core.type.TypeReference<List<McpProperties.ServerConfig>>() {});
            for (McpProperties.ServerConfig config : configs) {
                serverConfigs.put(config.getName(), config);
            }
        } catch (Exception e) {
            System.err.println("[MCP Manager] Failed to load dynamic servers: " + e.getMessage());
        }
    }

    private void saveDynamicServers() {
        File file = new File(sandboxDir, "dynamic-mcp.json");
        try {
            file.getParentFile().mkdirs();
            List<McpProperties.ServerConfig> configs = new ArrayList<>();
            for (Map.Entry<String, McpProperties.ServerConfig> entry : serverConfigs.entrySet()) {
                if (!isStaticServer(entry.getKey())) {
                    configs.add(entry.getValue());
                }
            }
            objectMapper.writeValue(file, configs);
        } catch (Exception e) {
            System.err.println("[MCP Manager] Failed to save dynamic servers: " + e.getMessage());
        }
    }

    private boolean isStaticServer(String name) {
        if (mcpProperties.getServers() == null) return false;
        return mcpProperties.getServers().stream().anyMatch(s -> s.getName().equalsIgnoreCase(name));
    }

    /**
     * Lists and converts all tools exposed by active MCP servers into LangChain4j specifications.
     */
    public List<ToolSpecification> getMcpToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Map.Entry<String, McpSyncClient> entry : serverClients.entrySet()) {
            String serverName = entry.getKey();
            McpSyncClient client = entry.getValue();
            try {
                McpSchema.ListToolsResult listToolsResult = client.listTools();
                if (listToolsResult != null && listToolsResult.tools() != null) {
                    List<String> tools = new ArrayList<>();
                    for (McpSchema.Tool mcpTool : listToolsResult.tools()) {
                        ToolSpecification spec = convertMcpToolToLangchain(mcpTool);
                        specs.add(spec);
                        toolToClientMap.put(mcpTool.name(), client);
                        tools.add(mcpTool.name());
                    }
                    serverTools.put(serverName, tools);
                }
            } catch (Exception e) {
                System.err.println("[MCP Manager] Failed to list tools for client " + serverName + ": " + e.getMessage());
            }
        }
        return specs;
    }

    /**
     * Executes an MCP tool request via the corresponding client.
     */
    public String executeMcpTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = toolToClientMap.get(toolName);
        if (client == null) {
            return "Error: MCP tool " + toolName + " is not registered.";
        }
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            if (result == null || result.content() == null) {
                return "Success: Tool executed with empty response.";
            }

            StringBuilder sb = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    sb.append(textContent.text());
                } else {
                    sb.append(content.toString());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to execute MCP tool: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private ToolSpecification convertMcpToolToLangchain(McpSchema.Tool mcpTool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(mcpTool.name())
                .description(mcpTool.description());

        Object inputSchema = mcpTool.inputSchema();
        if (inputSchema != null) {
            Map<String, Object> schemaMap = objectMapper.convertValue(inputSchema, Map.class);
            if (schemaMap != null && schemaMap.containsKey("properties")) {
                JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

                Map<String, Object> properties = (Map<String, Object>) schemaMap.get("properties");
                if (properties != null) {
                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        String paramName = entry.getKey();
                        Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
                        String type = (String) paramSchema.getOrDefault("type", "string");
                        String description = (String) paramSchema.get("description");

                        if ("integer".equalsIgnoreCase(type) || "int".equalsIgnoreCase(type)) {
                            schemaBuilder.addIntegerProperty(paramName, description);
                        } else if ("number".equalsIgnoreCase(type) || "float".equalsIgnoreCase(type) || "double".equalsIgnoreCase(type)) {
                            schemaBuilder.addNumberProperty(paramName, description);
                        } else if ("boolean".equalsIgnoreCase(type)) {
                            schemaBuilder.addBooleanProperty(paramName, description);
                        } else if (paramSchema.containsKey("enum")) {
                            List<String> enumValues = (List<String>) paramSchema.get("enum");
                            schemaBuilder.addEnumProperty(paramName, enumValues, description);
                        } else {
                            schemaBuilder.addStringProperty(paramName, description);
                        }
                    }
                }

                List<String> required = (List<String>) schemaMap.get("required");
                if (required != null) {
                    schemaBuilder.required(required);
                }

                builder.parameters(schemaBuilder.build());
            }
        }

        return builder.build();
    }
}
