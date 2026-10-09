package com.ekina.bootstrap.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.mcp.spec.McpSchema;
import org.springframework.context.annotation.Configuration;
import java.lang.reflect.Field;

/**
 * Custom configuration to patch Spring AI MCP deserialization strictness.
 * Dynamically disables FAIL_ON_UNKNOWN_PROPERTIES in McpSchema.OBJECT_MAPPER to
 * support third-party or custom MCP server extensions seamlessly.
 */
@Configuration
public class McpJacksonConfig {

    @PostConstruct
    public void configureMcpJackson() {
        try {
            System.out.println("[McpJacksonConfig] Configuring McpSchema.OBJECT_MAPPER to ignore unknown properties...");
            Field f = McpSchema.class.getDeclaredField("OBJECT_MAPPER");
            f.setAccessible(true);
            ObjectMapper mapper = (ObjectMapper) f.get(null);
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            System.out.println("[McpJacksonConfig] McpSchema.OBJECT_MAPPER successfully configured to ignore unknown fields.");
        } catch (Exception e) {
            System.err.println("[McpJacksonConfig] Error patching McpSchema.OBJECT_MAPPER: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
