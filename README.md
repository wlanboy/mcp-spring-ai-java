# MCP Hello World Server — Spring AI / Java 25

Ein minimaler MCP-Server (Model Context Protocol) auf Basis von Spring Boot 4 und Spring AI 2.0.

## Voraussetzungen

- Java 25
- Maven 3.9+

## Arbeitsschritte

### 1. Projektgerüst mit Spring Initializr erzeugen

```bash
curl -s -o spring-init.zip "https://start.spring.io/starter.zip?\
type=maven-project&language=java&bootVersion=4.1.0\
&groupId=com.example&artifactId=helloworld\
&packageName=com.example.helloworld&javaVersion=25\
&dependencies=configuration-processor,spring-ai-mcp-server,webflux"
unzip spring-init.zip
```

Spring Initializr liefert ein fertiges Maven-Projekt mit `mvnw`, `.gitignore` und
einer leeren `HelloworldApplication.java`.

> **Hinweis:** `spring-ai-mcp-server` erzeugt den Servlet-Starter
> `spring-ai-starter-mcp-server`. In Schritt 2b wird das Artifact auf die
> WebFlux-Variante `spring-ai-starter-mcp-server-webflux` geändert.

---

### 2. pom.xml anpassen

Drei Anpassungen gegenüber dem generierten Stand:

**a) `start-class` in `<properties>` eintragen** (für AOT-fähiges Packaging):

```xml
<properties>
    <java.version>25</java.version>
    <start-class>com.example.helloworld.HelloworldApplication</start-class>
</properties>
```

**b) Generierten MCP-Starter auf WebMVC oder WebFlux-Variante umstellen:**

Der Initializr erzeugt den Servlet-basierten Starter. Den `artifactId` auf die
reaktive Variante ändern:

```xml
<!-- generiert (ersetzen): -->
<artifactId>spring-ai-starter-mcp-server</artifactId>

<!-- ersetzen durch: -->
<artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>

<!-- ersetzen durch: -->
<artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
```

Der Starter zieht WebFlux, Reactor und den MCP-Protokollstack selbst mit —
`spring-boot-starter-webflux` muss nicht separat eingetragen werden.

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
public ToolCallbackProvider helloWorldToolProvider(HelloWorldTools helloWorldTools) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(helloWorldTools)
        .build();
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

#### Automatisiert mit `curl-test.sh`

```bash
./curl-test.sh
```

Das Script durchläuft den kompletten MCP-Protokollablauf:

1. Öffnet eine SSE-Verbindung zu `/sse` und liest den Session-Endpoint aus dem Stream
2. Sendet `initialize` (Handshake mit Protokollversion)
3. Sendet `notifications/initialized` (Bestätigung)
4. Ruft `tools/list` auf — zeigt alle registrierten Tools
5. Ruft `tools/call greet` mit `name=World` auf
6. Ruft `tools/call serverTime` auf

Beispielausgabe:

```
Connecting to http://localhost:8080/sse ...
Session endpoint: /mcp/message?sessionId=003c8063-07bb-49a3-ac42-95e048a10f3a

[initialize]
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "protocolVersion": "2024-11-05",
        "capabilities": {
            "completions": {},
            "logging": {},
            "prompts": { "listChanged": true },
            "resources": { "subscribe": false, "listChanged": true },
            "tools": { "listChanged": true }
        },
        "serverInfo": {
            "name": "hello-world-mcp",
            "version": "1.0.0"
        }
    }
}

[notifications/initialized] sent

[tools/list]
{
    "jsonrpc": "2.0",
    "id": 2,
    "result": {
        "tools": [
            {
                "name": "greet",
                "description": "Returns a greeting message for the given name",
                "inputSchema": {
                    "type": "object",
                    "properties": { "name": { "type": "string" } },
                    "required": ["name"]
                }
            },
            {
                "name": "serverTime",
                "description": "Returns the current server time as ISO-8601 string",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
            }
        ]
    }
}
  Verfügbare Tools:
    - greet: Returns a greeting message for the given name
    - serverTime: Returns the current server time as ISO-8601 string

[tools/call greet]
{
    "jsonrpc": "2.0",
    "id": 3,
    "result": {
        "content": [{ "type": "text", "text": "\"Hello, World! Welcome to the MCP Hello World Server.\"" }],
        "isError": false
    }
}
  => "Hello, World! Welcome to the MCP Hello World Server."

[tools/call serverTime]
{
    "jsonrpc": "2.0",
    "id": 4,
    "result": {
        "content": [{ "type": "text", "text": "\"2026-06-04T08:10:42.098489232Z\"" }],
        "isError": false
    }
}
  => "2026-06-04T08:10:42.098489232Z"
```

Der Server-URL kann über die Umgebungsvariable `MCP_SERVER` überschrieben werden:

```bash
MCP_SERVER=http://myserver:9090 ./curl-test.sh
```

#### Manuell — SSE-Stream direkt beobachten

```bash
curl -N http://localhost:8080/sse
```

---

## LLM Studio

```json
{
  "mcpServers": {
    "hello-world-mcp": {
      "type": "sse",
      "url": "http://localhost:8080/sse"
    }
  }
}

``` 