package com.example.helloworld;

import java.time.Instant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class HelloWorldTools {

    @Tool(description = "Returns a greeting message for the given name")
    public String greet(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("name must not exceed 100 characters");
        }
        return "Hello, %s! Welcome to the MCP Hello World Server.".formatted(name.strip());
    }

    @Tool(description = "Returns the current server time as ISO-8601 string")
    public String serverTime() {
        return Instant.now().toString();
    }
}
