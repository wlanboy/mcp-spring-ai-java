package com.example.helloworld;

import java.time.Instant;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class HelloWorldTools {

    // Both tools are pure, side-effect-free lookups: readOnlyHint/idempotentHint = true,
    // destructiveHint = false, openWorldHint = false (no external/unpredictable systems involved).
    @McpTool(title = "Greet", description = "Returns a greeting message for the given name",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String greet(@McpToolParam(description = "Name to greet", required = true) String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("name must not exceed 100 characters");
        }
        return "Hello, %s! Welcome to the MCP Hello World Server.".formatted(name.strip());
    }

    @McpTool(title = "Server Time", description = "Returns the current server time as ISO-8601 string",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String serverTime() {
        return Instant.now().toString();
    }
}
