package com.example.helloworld;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class HelloWorldTools {

    @Tool(description = "Returns a greeting message for the given name")
    public String greet(String name) {
        return "Hello, %s! Welcome to the MCP Hello World Server.".formatted(name);
    }

    @Tool(description = "Returns the current server time as ISO-8601 string")
    public String serverTime() {
        return java.time.Instant.now().toString();
    }
}
