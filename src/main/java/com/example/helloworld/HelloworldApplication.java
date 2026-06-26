package com.example.helloworld;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class HelloworldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelloworldApplication.class, args);
	}

	@Bean
	public ToolCallbackProvider helloWorldToolCallbacks(HelloWorldTools helloWorldTools,
			JavaProcessTools javaProcessTools, NodeExporterTools nodeExporterTools, SystemHealthTools systemHealthTools) {
		return MethodToolCallbackProvider.builder().toolObjects(helloWorldTools, javaProcessTools, nodeExporterTools, systemHealthTools).build();
	}
}
