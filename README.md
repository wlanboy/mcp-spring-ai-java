# MCP System-Monitoring Server — Spring AI / Java 25

Ein MCP-Server (Model Context Protocol) auf Basis von Spring Boot 4 und Spring AI 2.0.
Neben einem Hello-World-Beispiel stellt er reale System-Monitoring-Tools bereit: laufende
Java-Prozesse, Host-Metriken über den Prometheus `node_exporter` sowie eine aggregierte
Health-Check-Auswertung mit konfigurierbaren Schwellwerten.

Eckdaten:

- Transport: **Streamable HTTP** (`POST /mcp`), MCP-Protokollversion bis `2025-11-25`
- Tools per `@McpTool`-Annotation, mit Titel, Tool-Annotations (`readOnlyHint` …) und
  **Structured Output** (`outputSchema` + `structuredContent`)
- Progress- und Log-Notifications für die Tools, die über 1 Sekunde messen
- Server-`instructions` im Handshake, die dem LLM den Einstieg erklären
- Docker-Image mit Spring **AOT** für schnelleren Start

## Voraussetzungen

- Java 25
- Maven 3.9+
- Docker (optional, nur für [`nodeexporter.sh`](nodeexporter.sh) — Voraussetzung für alle
  `NodeExporterTools`- und `SystemHealthTools`-Tools)

## Arbeitsschritte

### 1. Projektgerüst mit Spring Initializr erzeugen

```bash
curl -s -o spring-init.zip "https://start.spring.io/starter.zip?\
type=maven-project&language=java&bootVersion=4.1.2\
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

Aktuell im Projekt verwendet: `spring-boot-starter-parent` `4.1.2`,
`spring-ai-starter-mcp-server-webflux` `2.0.2` (GA-Release, bringt das MCP Java SDK `2.1.0` mit).

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

MCP-Tools sind einfache Spring-Beans, deren Methoden mit `@McpTool` annotiert werden.
`description` erscheint im MCP-Toolkatalog und wird vom KI-Modell für die Tool-Auswahl
genutzt, `title` ist der Anzeigename im Client. Die `annotations` beschreiben das
Verhalten des Tools (hier: rein lesend, idempotent, keine externen Systeme).
Minimalbeispiel (`HelloWorldTools.java`):

```java
@Service
public class HelloWorldTools {

    @McpTool(title = "Greet", description = "Returns a greeting message for the given name",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String greet(@McpToolParam(description = "Name to greet", required = true) String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return "Hello, %s! Welcome to the MCP Hello World Server.".formatted(name.strip());
    }
}
```

**Structured Output:** Tools, die einen Record zurückgeben, setzen
`generateOutputSchema = true`. Der Client bekommt dann in `tools/list` ein `outputSchema`
und beim Aufruf das Ergebnis typisiert als `structuredContent` (plus dasselbe JSON als
Text-Content für ältere Clients). Die MCP-Spezifikation verlangt dafür ein JSON-*Objekt* —
Listen werden deshalb in einen Record verpackt:

```java
record DiskSpaceReport(List<DiskSpace> filesystems) {}

@McpTool(title = "Disk Space", generateOutputSchema = true, description = "...")
public DiskSpaceReport getDiskSpace() { ... }
```

**Fehlerbehandlung:** Tool-Methoden werfen nur unchecked Exceptions. Spring AI macht
daraus ein Ergebnis mit `isError: true` und der Exception-Message, die das LLM lesen kann.
Checked Exceptions würden dagegen als nichtssagender JSON-RPC-Fehler
`-32603 Internal error` beim Client ankommen.

**Progress/Logging:** Tools, die über eine Sekunde messen, nehmen einen
`McpSyncRequestContext`-Parameter entgegen (taucht nicht im Input-Schema auf) und melden
darüber `ctx.info(...)` und `ctx.progress(...)` an den Client.

Das Projekt enthält daneben drei weitere Tool-Klassen mit insgesamt 13 zusätzlichen
Tools — siehe [Verfügbare Tools](#verfügbare-tools) weiter unten.

---

### 4. Application-Klasse (`HelloworldApplication.java`)

Eine explizite Registrierung der Tools (früher per `ToolCallbackProvider`-Bean) ist nicht
mehr nötig: Der Annotation-Scanner von Spring AI findet alle `@McpTool`-/`@McpResource`-
Methoden auf Spring-Beans automatisch.

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class HelloworldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelloworldApplication.class, args);
	}
}
```

`@ConfigurationPropertiesScan` registriert die typisierte Konfiguration
[`HealthThresholds`](src/main/java/com/example/helloworld/HealthThresholds.java)
(`health.threshold.*`, siehe nächster Abschnitt).

---

### 5. application.properties konfigurieren

```properties
spring.application.name=helloworld

spring.ai.mcp.server.name=hello-world-mcp
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.instructions=Read-only monitoring server for a single Linux host. \
Start with getSystemAnomalies for an overall health check ...

server.port=8099

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

- `type=SYNC`: Alle Tool-Methoden sind blockierend (HttpClient, `Thread.sleep`,
  `ProcessBuilder`). Im `ASYNC`-Modus registriert Spring AI nur Methoden mit
  `Mono`/`Flux`-Rückgabe — die imperativen Tools würden stillschweigend fehlen.
- `protocol=STREAMABLE`: Streamable HTTP mit einem einzigen Endpoint `POST /mcp`. Der
  frühere SSE-Transport (`/sse` + `/mcp/message`) ist seit Spring AI 2.0 deprecated, ist
  beim WebFlux-Starter aber weiterhin der Default — daher die explizite Angabe.
- `instructions` wird beim `initialize` an den Client geschickt und erklärt dem LLM, wie
  es die Tools sinnvoll nutzt (erst `getSystemAnomalies`, dann gezielt nachfassen).
- `name` und `version` erscheinen im MCP-Handshake.
- `node-exporter.url` zeigt auf den `/metrics`-Endpunkt eines laufenden `node_exporter`
  (Default: `http://localhost:9100/metrics`, siehe unten). Per Umgebungsvariable
  `NODEEXPORTER_URL` überschreibbar (Relaxed Binding, siehe `docker-compose.yml`).
- Die `health.threshold.*`-Werte werden in
  [`HealthThresholds`](src/main/java/com/example/helloworld/HealthThresholds.java)
  gebunden (`@ConfigurationProperties`). Jeder Wert hat dort einen eigenen Default, so dass
  auch einzelne Werte überschrieben werden können (z.B. nur `health.threshold.cpu.warning`).
  Die IDE bietet für alle Keys Autovervollständigung an (Metadaten vom
  `spring-boot-configuration-processor`).

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

Zum Stoppen und Entfernen des Containers dient [`nodeexporter-delete.sh`](nodeexporter-delete.sh):

```bash
./nodeexporter-delete.sh
```

Ohne laufenden `node_exporter` liefern alle `NodeExporterTools`- und
`SystemHealthTools`-Aufrufe `isError: true` mit der Meldung
`node_exporter not reachable at ... - is node_exporter running?`; `HelloWorldTools` und
`JavaProcessTools` sind davon unabhängig nutzbar.

Für lokale Tests ohne echten Exporter liefert [`fake_node_exporter.py`](fake_node_exporter.py)
statische Beispielmetriken auf Port 9100:

```bash
python3 fake_node_exporter.py
```

---

### 7. Server starten

```bash
./mvnw spring-boot:run
```

Der Server lauscht auf `http://localhost:8099`, der MCP-Endpoint ist `http://localhost:8099/mcp`.

#### Mit Spring AOT

Wie im Docker-Image: AOT-Metadaten beim Build erzeugen und zur Laufzeit aktivieren.

```bash
./mvnw -DskipTests compile spring-boot:process-aot package
java -Dspring.aot.enabled=true -jar target/helloworld-0.0.1-SNAPSHOT.jar
```

Im Log steht dann `Starting AOT-processed HelloworldApplication`. Bedingungen auf Basis
von Properties (z.B. `spring.ai.mcp.server.protocol`) werden bei AOT schon zur Build-Zeit
ausgewertet — wer sie ändert, muss neu bauen. Normale Werte wie `node-exporter.url` oder
die Schwellwerte bleiben zur Laufzeit überschreibbar.

#### Alternativ: Docker Compose (MCP-Server + Node Exporter)

[`docker-compose.yml`](docker-compose.yml) startet `node-exporter` (wie in
[`nodeexporter.sh`](nodeexporter.sh) im Host-Netzwerk) und den MCP-Server, gebaut aus dem
[`Dockerfile`](Dockerfile) (Fat-JAR mit Spring AOT, `-Dspring.aot.enabled=true`):

```bash
docker compose up -d --build
docker compose down
```

Der MCP-Server ist unter `http://localhost:8099` erreichbar und erreicht den Node Exporter
über `host.docker.internal:9100` (gesetzt über die Umgebungsvariable `NODEEXPORTER_URL`).
`listJavaProcesses` liefert im Container eine leere Liste, da das Runtime-Image weder `jps`
noch `ps` enthält und nur die Prozesse des eigenen Containers sieht.

---

### 8. Tests ausführen

```bash
./mvnw test
```

Deckt alle vier Tool-Klassen, das Binding der Schwellwerte sowie den
Application-Context-Start ab (`HelloWorldToolsTest`, `JavaProcessToolsTest`,
`NodeExporterToolsTest`, `SystemHealthToolsTest`, `HealthThresholdsTest`,
`HelloworldApplicationTests`).

---

### 9. MCP-Endpunkte testen

#### Automatisiert mit `curl-test.sh`

```bash
./curl-test.sh
```

Das Script durchläuft den kompletten MCP-Protokollablauf über Streamable HTTP
(alle Requests als `POST /mcp`):

1. Sendet `initialize` (Handshake mit Protokollversion `2025-11-25`) und liest die
   Session-ID aus dem Response-Header `Mcp-Session-Id`
2. Sendet `notifications/initialized` (Bestätigung) — ab hier mit `Mcp-Session-Id`-Header
3. Ruft `tools/list` auf — zeigt alle registrierten Tools mit Titel und markiert Tools mit `outputSchema`
4. Ruft `tools/call greet` mit `name=World` auf
5. Ruft `tools/call serverTime` auf
6. Beendet die Session per `DELETE /mcp`

Der Server antwortet je nach Request mit `application/json` oder als SSE-Stream
(`text/event-stream`); das Script wertet beides aus.

Beispielausgabe (gekürzt):

```
Connecting to http://localhost:8099/mcp ...
[initialize]
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "protocolVersion": "2025-11-25",
        "capabilities": { ... },
        "serverInfo": { "name": "hello-world-mcp", "version": "1.0.0" },
        "instructions": "Read-only monitoring server for a single Linux host. ..."
    }
}

Session: 51aeb62d-a640-464c-ba48-5ab170a2d118

[notifications/initialized] sent

[tools/list]
  Verfügbare Tools:
    - greet (Greet): Returns a greeting message for the given name
    - serverTime (Server Time): Returns the current server time as ISO-8601 string
    - listJavaProcesses (Java Processes) [outputSchema]: Lists all running Java processes ...
    - getMemoryStats (Memory Usage) [outputSchema]: Returns system memory stats ...
    ...

[tools/call greet]
{
    "jsonrpc": "2.0",
    "id": 3,
    "result": {
        "content": [{ "type": "text", "text": "Hello, World! Welcome to the MCP Hello World Server." }],
        "isError": false
    }
}
  => Hello, World! Welcome to the MCP Hello World Server.
```

Tools mit `outputSchema` liefern ihr Ergebnis zusätzlich typisiert als `structuredContent`
(neben demselben JSON als Text-Content für ältere Clients). Ist der `node_exporter` nicht
erreichbar, liefern die betroffenen Tools `isError: true` mit einer lesbaren Meldung
(`node_exporter not reachable at ... - is node_exporter running?`).

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

#### Manuell — initialize per curl

```bash
curl -i http://localhost:8099/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

Der Header `Mcp-Session-Id` aus der Antwort muss bei allen weiteren Requests mitgeschickt werden.

---

## Verfügbare Tools

Alle Tools sind rein lesend (`readOnlyHint = true`). Die Spalte *Ergebnis* zeigt die
Form des `structuredContent`; Tools ohne Eintrag liefern nur Text.

### HelloWorldTools

| Tool | Titel | Beschreibung | Ergebnis |
|---|---|---|---|
| `greet(name)` | Greet | Begrüßung für den übergebenen Namen (validiert: nicht leer, max. 100 Zeichen) | — |
| `serverTime()` | Server Time | Aktuelle Serverzeit als ISO-8601-String | — |

### JavaProcessTools

| Tool | Titel | Beschreibung | Ergebnis |
|---|---|---|---|
| `listJavaProcesses()` | Java Processes | Alle laufenden Java-Prozesse mit PID, Name, CPU % und RAM (MB), via `jps`/`ps` | `{processes: [...]}` |

### NodeExporterTools

*Benötigt einen laufenden `node_exporter`, siehe [Node Exporter starten](#6-node-exporter-starten).*

| Tool | Titel | Beschreibung | Ergebnis |
|---|---|---|---|
| `getMemoryStats()` | Memory Usage | RAM: total, verfügbar, belegt (MB) + Prozent | Objekt |
| `getSwapStats()` | Swap Usage | Swap: total, frei, belegt (MB) + Prozent | Objekt |
| `getSystemLoad()` | System Load | Load Average für 1/5/15 Minuten | Objekt |
| `getCpuUsage()` | CPU Usage | CPU-Auslastung (user/system/idle) über 1 Sekunde gemessen, alle Kerne | Objekt |
| `getNetworkStats()` | Network Traffic | Netzwerktraffic (MB empfangen/gesendet) pro Interface | `{interfaces: [...]}` |
| `getSystemUptime()` | System Uptime | Uptime seit letztem Boot (Stunden/Minuten) | — |
| `getDiskActivity()` | Disk Activity | Disk-I/O (MB/s Lesen/Schreiben) pro Gerät, über 1 Sekunde gemessen | `{devices: [...]}` |
| `getDiskSpace()` | Disk Space | Speicherplatz pro Dateisystem (Mountpoint, Device, Typ, GB, Prozent); virtuelle Dateisysteme ausgeschlossen | `{filesystems: [...]}` |
| `getNetworkErrors()` | Network Errors | Kumulative Netzwerkfehler/-drops pro Interface seit Systemstart | `{interfaces: [...]}` |
| `getTemperatures()` | Hardware Temperatures | Hardware-Temperaturen (°C) je Sensor inkl. kritischem Schwellwert, absteigend sortiert | `{sensors: [...]}` |
| `getPressureStats()` | Pressure Stall Information | Linux PSI (Pressure Stall Information) für CPU/IO/Memory, über 1 Sekunde gemessen | Objekt |

Zusätzlich als **MCP-Resource** (statt Tool, da statische Host-Info):

| Resource-URI | Beschreibung |
|---|---|
| `system://info` | Hostname, Kernel-Version, OS-Typ, Architektur (JSON) |

### SystemHealthTools

*Baut auf `NodeExporterTools` auf, benötigt ebenfalls einen laufenden `node_exporter`.*

| Tool | Titel | Beschreibung | Ergebnis |
|---|---|---|---|
| `getSystemAnomalies()` | System Health Check | Prüft CPU, Memory, Swap, Diskspace, System Load, Temperaturen und PSI gegen die konfigurierten Schwellwerte (`health.threshold.*`). Liefert die schlechteste Severity als Gesamturteil plus je Metrik einen Befund mit Severity `OK`/`WARNING`/`CRITICAL`. Die Load wird gegen die Kernzahl des Hosts laut `node_exporter` geprüft. | `{overall, findings: [...]}` |

---

## LLM Studio

```json
{
  "mcpServers": {
    "hello-world-mcp": {
      "url": "http://localhost:8099/mcp"
    }
  }
}
```
