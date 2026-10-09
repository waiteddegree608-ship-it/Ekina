package com.ekina.infrastructure.agent.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "ekina.mcp")
@Data
public class McpProperties {
    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private String name;
        private String type; // "stdio" or "sse"
        private String command;
        private List<String> args = new ArrayList<>();
        private String url; // For SSE
    }
}
