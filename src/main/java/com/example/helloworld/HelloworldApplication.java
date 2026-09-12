package com.example.helloworld;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// No ToolCallbackProvider bean needed anymore: all tools/resources use the
// @McpTool/@McpResource annotations (Spring AI MCP 2.0), which are picked up
// automatically by annotation scanning on @Service beans.
@SpringBootApplication
public class HelloworldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelloworldApplication.class, args);
	}
}
