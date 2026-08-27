# MCP System-Monitoring Server — Spring AI / Java 25

Ein MCP-Server (Model Context Protocol) auf Basis von Spring Boot 4 und Spring AI 2.0.
Neben einem Hello-World-Beispiel stellt er reale System-Monitoring-Tools bereit: laufende
Java-Prozesse, Host-Metriken über den Prometheus `node_exporter` sowie eine aggregierte
Health-Check-Auswertung mit konfigurierbaren Schwellwerten.

## Voraussetzungen

- Java 25
- Maven 3.9+
- Docker (optional, nur für [`nodeexporter.sh`](nodeexporter.sh) — Voraussetzung für alle
  `NodeExporterTools`- und `SystemHealthTools`-Tools)

## Arbeitsschritte

### 1. Projektgerüst mit Spring Initializr erzeugen

```bash
curl -s -o spring-init.zip "https://start.spring.io/starter.zip?\
type=maven-project&language=java&bootVersion=4.1.1\
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

<!-- oder ersetzen durch: -->
<artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
```

Der Starter zieht WebFlux, Reactor und den MCP-Protokollstack selbst mit —
`spring-boot-starter-webflux` muss nicht separat eingetragen werden.

Aktuell im Projekt verwendet: `spring-boot-starter-parent` `4.1.1`,
`spring-ai-starter-mcp-server-webflux` `2.0.1` (GA-Release).

**c) Spring Milestones Repository:**

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

> **Hinweis:** Dieses Repository war ursprünglich für Meilenstein-Builds von Spring AI
> nötig. Seit dem GA-Release `2.0.1` bezieht das Projekt alle Dependencies bereits aus
> Maven Central; das Repository ist in der `pom.xml` weiterhin vorhanden, wird aber
> aktuell nicht mehr zwingend benötigt.

---

### 3. Tool-Klassen erstellen

MCP-Tools sind einfache Spring-Beans, deren Methoden mit `@Tool` annotiert werden.
Die `description` erscheint im MCP-Toolkatalog und wird vom KI-Modell für die
Tool-Auswahl genutzt. Minimalbeispiel (`HelloWorldTools.java`):

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

Das Projekt enthält daneben drei weitere Tool-Klassen mit insgesamt 13 zusätzlichen
Tools — siehe [Verfügbare Tools](#verfügbare-tools) weiter unten.

---

### 4. Tools als Bean registrieren (`HelloworldApplication.java`)

Spring AI benötigt einen `ToolCallbackProvider`-Bean, der dem MCP-Server mitteilt,
welche Tools exportiert werden sollen. Alle Tool-Beans werden hier gebündelt:

```java
@Bean
public ToolCallbackProvider helloWorldToolCallbacks(
        HelloWorldTools helloWorldTools,
        JavaProcessTools javaProcessTools,
        NodeExporterTools nodeExporterTools,
        SystemHealthTools systemHealthTools) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(helloWorldTools, javaProcessTools, nodeExporterTools, systemHealthTools)
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

# URL des Prometheus node_exporter (siehe Abschnitt "Node Exporter starten")
node-exporter.url=http://localhost:9100/metrics

# Schwellwerte für SystemHealthTools#getSystemAnomalies (Prozent, sofern nicht anders angegeben)
health.threshold.cpu.warning=70
health.threshold.cpu.critical=90
health.threshold.memory.warning=80
health.threshold.memory.critical=90
health.threshold.swap.warning=70
health.threshold.swap.critical=90
health.threshold.disk.warning=80
health.threshold.disk.critical=90
# Temperatur-Schwellwerte als Prozentsatz des sensoreigenen kritischen Werts
health.threshold.temperature.warning=80
health.threshold.temperature.critical=95
# PSI-Schwellwerte in Prozent gestallter/wartender Zeit pro 1-Sekunden-Fenster
health.threshold.psi.cpu.some.warning=30
health.threshold.psi.cpu.some.critical=70
health.threshold.psi.io.full.warning=5
health.threshold.psi.io.full.critical=20
health.threshold.psi.memory.full.warning=1
health.threshold.psi.memory.full.critical=10
```

- `type=ASYNC` aktiviert den reaktiven SSE-Transport (passend zu WebFlux).
- `name` und `version` erscheinen im MCP-Handshake.
- `node-exporter.url` zeigt auf den `/metrics`-Endpunkt eines laufenden `node_exporter`
  (Default: `http://localhost:9100/metrics`, siehe unten).
- Alle `health.threshold.*`-Werte haben Defaults im Code (`@Value("...:default")`) und
  müssen nicht zwingend gesetzt werden.

---

### 6. Node Exporter starten

`NodeExporterTools` und darauf aufbauend `SystemHealthTools` benötigen einen laufenden
Prometheus `node_exporter` als Metrik-Quelle. [`nodeexporter.sh`](nodeexporter.sh) startet
ihn als Docker-Container mit Host-Netzwerk und Zugriff auf das Host-Root-Dateisystem
(read-only):

```bash
./nodeexporter.sh
```

Das Skript entfernt einen evtl. vorhandenen Container namens `node-exporter`, startet
`prom/node-exporter:latest` neu mit `--restart=always` und mountet `/` read-only nach
`/host/root`. Danach ist der Endpunkt unter `http://localhost:9100/metrics` erreichbar.

Ohne laufenden `node_exporter` schlagen alle `NodeExporterTools`- und
`SystemHealthTools`-Aufrufe mit einem HTTP-Fehler fehl; `HelloWorldTools` und
`JavaProcessTools` sind davon unabhängig nutzbar.

---

### 7. Server starten

```bash
./mvnw spring-boot:run
```

Der Server lauscht auf `http://localhost:8080`.

---

### 8. Tests ausführen

```bash
./mvnw test
```

Deckt alle vier Tool-Klassen sowie den Application-Context-Start ab
(`HelloWorldToolsTest`, `JavaProcessToolsTest`, `NodeExporterToolsTest`,
`SystemHealthToolsTest`, `HelloworldApplicationTests`).

---

### 9. MCP-Endpunkte testen

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
        "tools": [ ... 17 Tools ... ]
    }
}

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

#### Automatisiert mit `curl-java-test.sh`

```bash
./curl-java-test.sh
```

Führt denselben Handshake wie `curl-test.sh` durch und ruft anschließend
`tools/call listJavaProcesses` auf. Die zurückgegebene Prozessliste wird als
formatierte Tabelle (PID, Name, CPU %, RAM MB) ausgegeben. Respektiert ebenfalls
`MCP_SERVER`.

#### Manuell — SSE-Stream direkt beobachten

```bash
curl -N http://localhost:8080/sse
```

---

## Verfügbare Tools

### HelloWorldTools

| Tool | Beschreibung |
|---|---|
| `greet(name)` | Begrüßung für den übergebenen Namen (validiert: nicht leer, max. 100 Zeichen) |
| `serverTime()` | Aktuelle Serverzeit als ISO-8601-String |

### JavaProcessTools

| Tool | Beschreibung |
|---|---|
| `listJavaProcesses()` | Alle laufenden Java-Prozesse mit PID, Name, CPU % und RAM (MB), via `jps`/`ps` |

### NodeExporterTools

*Benötigt einen laufenden `node_exporter`, siehe [Node Exporter starten](#6-node-exporter-starten).*

| Tool | Beschreibung |
|---|---|
| `getMemoryStats()` | RAM: total, verfügbar, belegt (MB) + Prozent |
| `getSwapStats()` | Swap: total, frei, belegt (MB) + Prozent |
| `getSystemLoad()` | Load Average für 1/5/15 Minuten |
| `getCpuUsage()` | CPU-Auslastung (user/system/idle) über 1 Sekunde gemessen, alle Kerne |
| `getNetworkStats()` | Netzwerktraffic (MB empfangen/gesendet) pro Interface |
| `getSystemUptime()` | Uptime seit letztem Boot (Stunden/Minuten) |
| `getDiskActivity()` | Disk-I/O (MB/s Lesen/Schreiben) pro Gerät, über 1 Sekunde gemessen |
| `getDiskSpace()` | Speicherplatz pro Dateisystem (Mountpoint, Device, Typ, GB, Prozent); virtuelle Dateisysteme ausgeschlossen |
| `getNetworkErrors()` | Kumulative Netzwerkfehler/-drops pro Interface seit Systemstart |
| `getTemperatures()` | Hardware-Temperaturen (°C) je Sensor inkl. kritischem Schwellwert, absteigend sortiert |
| `getPressureStats()` | Linux PSI (Pressure Stall Information) für CPU/IO/Memory, über 1 Sekunde gemessen |
| `getSystemInfo()` | Hostname, Kernel-Version, OS-Typ, Architektur |

### SystemHealthTools

*Baut auf `NodeExporterTools` auf, benötigt ebenfalls einen laufenden `node_exporter`.*

| Tool | Beschreibung |
|---|---|
| `getSystemAnomalies()` | Prüft CPU, Memory, Swap, Diskspace, System Load, Temperaturen und PSI gegen die in `application.properties` konfigurierten Schwellwerte. Liefert je Metrik einen Befund mit Severity `OK`/`WARNING`/`CRITICAL`. |

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
