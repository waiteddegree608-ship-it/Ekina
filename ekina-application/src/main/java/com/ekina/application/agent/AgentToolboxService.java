package com.ekina.application.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;

/**
 * Application layer interface for the agent's toolbox.
 * Decouples the agent execution engine from concrete tool implementations and MCP servers.
 */
public interface AgentToolboxService {

    /**
     * Retrieves all tool specifications (both local and MCP tools).
     *
     * @return List of tool specifications for LangChain4j agent consumption
     */
    List<ToolSpecification> getToolSpecifications();

    /**
     * Executes the specified tool method dynamically.
     *
     * @param toolName  The name of the tool to execute
     * @param arguments The arguments map for the execution
     * @return Execution result string
     */
    String executeTool(String toolName, Map<String, Object> arguments);
}
