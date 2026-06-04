# MCP Hello World Server — Spring AI / Java 25

Ein minimaler MCP-Server (Model Context Protocol) auf Basis von Spring Boot 4 und Spring AI 2.0.

## Voraussetzungen

- Java 25
- Maven 3.9+

## Arbeitsschritte

### 1. Projektgerüst mit Spring Initializr erzeugen

```bash
curl -s -o spring-init.zip "https://start.spring.io/starter.zip?\
type=maven-project&language=java&bootVersion=4.0.6\
&groupId=com.example&artifactId=helloworld\
&packageName=com.example.helloworld&javaVersion=25\
&dependencies=configuration-processor"
unzip spring-init.zip
```

Spring Initializr liefert ein fertiges Maven-Projekt mit `mvnw`, `.gitignore` und
einer leeren `HelloworldApplication.java`.

---

### 2. pom.xml anpassen

Zwei Ergänzungen gegenüber dem generierten Stand:

**a) `start-class` in `<properties>` eintragen** (für AOT-fähiges Packaging):

```xml
<properties>
    <java.version>25</java.version>
    <start-class>com.example.helloworld.HelloworldApplication</start-class>
</properties>
```

**b) MCP-Server-Starter hinzufügen** (Spring AI WebFlux / SSE-Transport):

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
    <version>2.0.0-M8</version>
</dependency>
```

**c) Spring Milestones Repository** (da M8 noch kein GA-Release ist):

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

Der `spring-boot-starter` aus dem Initializr-Template wird durch
`spring-ai-starter-mcp-server-webflux` ersetzt — der Starter zieht
WebFlux, Reactor und den MCP-Protokollstack selbst mit.

---

### 3. Tool-Klasse erstellen (`HelloWorldTools.java`)

MCP-Tools sind einfache Spring-Beans, deren Methoden mit `@Tool` annotiert
werden. Die `description` erscheint im MCP-Toolkatalog und wird vom
KI-Modell für die Tool-Auswahl genutzt.

```java
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
```

---

### 4. Tools als Bean registrieren (`HelloworldApplication.java`)

Spring AI benötigt einen `ToolCallbackProvider`-Bean, der dem MCP-Server
mitteilt, welche Tools exportiert werden sollen:

```java
@Bean
public ToolCallbackProvider helloWorldTools(HelloWorldTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
}
```

---

### 5. application.properties konfigurieren

```properties
spring.application.name=helloworld

spring.ai.mcp.server.name=hello-world-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=ASYNC

server.port=8080
```

- `type=ASYNC` aktiviert den reaktiven SSE-Transport (passend zu WebFlux).
- `name` und `version` erscheinen im MCP-Handshake.

---

### 6. Server starten

```bash
./mvnw spring-boot:run
```

Der Server lauscht auf `http://localhost:8080`.

---

### 7. MCP-Endpunkte testen

**Tool-Liste abrufen (SSE):**

```bash
curl -N http://localhost:8080/sse
```

**greet-Tool aufrufen:**

```bash
curl -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "greet",
      "arguments": { "name": "World" }
    }
  }'
```

**serverTime-Tool aufrufen:**

```bash
curl -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "serverTime",
      "arguments": {}
    }
  }'
```

---

## Projektstruktur

```
src/
└── main/
    ├── java/com/example/helloworld/
    │   ├── HelloworldApplication.java   # Spring Boot Entry Point + Tool-Bean-Registrierung
    │   └── HelloWorldTools.java         # MCP-Tools mit @Tool-Annotationen
    └── resources/
        └── application.properties       # MCP-Server-Konfiguration
```

## Abhängigkeiten

| Artefakt | Version | Zweck |
|---|---|---|
| `spring-ai-starter-mcp-server-webflux` | 2.0.0-M8 | MCP-Protokoll + WebFlux/SSE-Transport |
| `spring-boot-configuration-processor` | 4.0.6 | IDE-Autovervollständigung für Properties |
