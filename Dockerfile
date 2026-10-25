FROM registry.access.redhat.com/ubi10/openjdk-25:latest AS build
WORKDIR /home/default/app

COPY --chown=185:185 pom.xml .
RUN --mount=type=cache,target=/home/default/.m2,uid=185 mvn -q dependency:go-offline

COPY --chown=185:185 src ./src
# compile -> process-aot (generates AOT bean definitions from the compiled classes) -> package
# (jar includes the AOT classes). Property-based conditions are evaluated at build time.
RUN --mount=type=cache,target=/home/default/.m2,uid=185 mvn -q -DskipTests compile spring-boot:process-aot package

FROM registry.access.redhat.com/ubi10/openjdk-25-runtime:latest

LABEL org.opencontainers.image.title="mcp-spring-ai-java" \
      org.opencontainers.image.description="Spring AI MCP Server mit Hello-World-, Java-Prozess-, Node-Exporter- und System-Health-Tools" \
      org.opencontainers.image.version="0.0.1-SNAPSHOT" \
      org.opencontainers.image.vendor="wlanboy" \
      org.opencontainers.image.source="https://github.com/wlanboy/mcp-spring-ai-java" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="openjdk-25-runtime:latest"

WORKDIR /app
COPY --from=build --chown=185:185 /home/default/app/target/helloworld-0.0.1-SNAPSHOT.jar app.jar

USER 185
EXPOSE 8099

ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]
