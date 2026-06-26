package com.example.helloworld;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NodeExporterTools {

    @Value("${node-exporter.url:http://localhost:9100/metrics}")
    private String metricsUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    record MemoryStats(long totalMb, long availableMb, long usedMb, double usedPercent) {}

    record SystemLoad(double load1, double load5, double load15) {}

    record NetworkInterface(String name, long receivedMb, long transmittedMb) {}

    record CpuUsage(double userPercent, double systemPercent, double idlePercent, double totalUsedPercent) {}

    @Tool(description = "Returns system memory stats: total, available and used in MB plus usage percentage")
    public MemoryStats getMemoryStats() throws Exception {
        Map<String, Double> metrics = fetchScalars();
        long total = toMb(metrics.getOrDefault("node_memory_MemTotal_bytes", 0.0));
        long available = toMb(metrics.getOrDefault("node_memory_MemAvailable_bytes", 0.0));
        long used = total - available;
        double pct = total > 0 ? Math.round((double) used / total * 1000.0) / 10.0 : 0.0;
        return new MemoryStats(total, available, used, pct);
    }

    @Tool(description = "Returns system load averages for the last 1, 5 and 15 minutes")
    public SystemLoad getSystemLoad() throws Exception {
        Map<String, Double> metrics = fetchScalars();
        return new SystemLoad(
                metrics.getOrDefault("node_load1", 0.0),
                metrics.getOrDefault("node_load5", 0.0),
                metrics.getOrDefault("node_load15", 0.0));
    }

    @Tool(description = "Returns CPU usage in percent (user, system, idle) measured over 1 second across all cores")
    public CpuUsage getCpuUsage() throws Exception {
        Map<String, Map<String, Double>> first = fetchCpuSeconds();
        Thread.sleep(1000);
        Map<String, Map<String, Double>> second = fetchCpuSeconds();

        double user = 0, system = 0, idle = 0, total = 0;
        for (String cpu : first.keySet()) {
            Map<String, Double> f = first.get(cpu);
            Map<String, Double> s = second.getOrDefault(cpu, Map.of());
            for (String mode : f.keySet()) {
                double delta = s.getOrDefault(mode, 0.0) - f.getOrDefault(mode, 0.0);
                total += delta;
                switch (mode) {
                    case "user" -> user += delta;
                    case "system" -> system += delta;
                    case "idle" -> idle += delta;
                }
            }
        }
        if (total == 0) return new CpuUsage(0, 0, 100, 0);
        double idleFinal = idle;
        return new CpuUsage(
                round(user / total * 100),
                round(system / total * 100),
                round(idleFinal / total * 100),
                round((total - idleFinal) / total * 100));
    }

    @Tool(description = "Returns network traffic in MB received and transmitted per network interface")
    public List<NetworkInterface> getNetworkStats() throws Exception {
        String raw = fetchRaw();
        Map<String, Long> received = new HashMap<>();
        Map<String, Long> transmitted = new HashMap<>();
        for (String line : raw.lines().toList()) {
            if (line.startsWith("node_network_receive_bytes_total{")) {
                received.put(extractLabel(line, "device"), (long) (extractValue(line) / 1_048_576));
            } else if (line.startsWith("node_network_transmit_bytes_total{")) {
                transmitted.put(extractLabel(line, "device"), (long) (extractValue(line) / 1_048_576));
            }
        }
        List<NetworkInterface> result = new ArrayList<>();
        for (String iface : received.keySet()) {
            result.add(new NetworkInterface(iface, received.get(iface), transmitted.getOrDefault(iface, 0L)));
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }

    @Tool(description = "Returns system uptime since last boot as hours and minutes")
    public String getSystemUptime() throws Exception {
        Map<String, Double> metrics = fetchScalars();
        double bootTime = metrics.getOrDefault("node_boot_time_seconds", 0.0);
        if (bootTime == 0) return "Unknown";
        long uptimeSec = Instant.now().getEpochSecond() - (long) bootTime;
        return "%d hours %d minutes".formatted(uptimeSec / 3600, (uptimeSec % 3600) / 60);
    }

    private Map<String, Map<String, Double>> fetchCpuSeconds() throws Exception {
        Map<String, Map<String, Double>> result = new HashMap<>();
        for (String line : fetchRaw().lines().toList()) {
            if (!line.startsWith("node_cpu_seconds_total{")) continue;
            result.computeIfAbsent(extractLabel(line, "cpu"), k -> new HashMap<>())
                    .put(extractLabel(line, "mode"), extractValue(line));
        }
        return result;
    }

    private Map<String, Double> fetchScalars() throws Exception {
        Map<String, Double> result = new HashMap<>();
        for (String line : fetchRaw().lines().toList()) {
            if (line.startsWith("#") || line.isBlank() || line.contains("{")) continue;
            int space = line.indexOf(' ');
            if (space < 0) continue;
            try {
                result.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1).trim()));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private String fetchRaw() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(metricsUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Node Exporter returned HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String extractLabel(String line, String key) {
        String search = key + "=\"";
        int start = line.indexOf(search);
        if (start < 0) return "unknown";
        start += search.length();
        int end = line.indexOf('"', start);
        return end < 0 ? "unknown" : line.substring(start, end);
    }

    private static double extractValue(String line) {
        int space = line.lastIndexOf(' ');
        if (space < 0) return 0;
        try {
            return Double.parseDouble(line.substring(space + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long toMb(double bytes) {
        return (long) bytes / 1_048_576;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
