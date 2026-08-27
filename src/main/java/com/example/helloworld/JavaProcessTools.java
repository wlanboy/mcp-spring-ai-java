package com.example.helloworld;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class JavaProcessTools {

    private static final Logger log = LoggerFactory.getLogger(JavaProcessTools.class);

    record JavaProcessInfo(long pid, String name, double cpuPercent, long ramMb) {}

    record ProcessStats(double cpu, long ramMb) {}

    @Tool(description = "Lists all running Java processes with PID, name, CPU usage (%) and RAM usage (MB)")
    public List<JavaProcessInfo> listJavaProcesses() {
        Map<Long, String> names = javaProcessNames();
        if (names.isEmpty()) return List.of();

        Map<Long, ProcessStats> stats = readStats(names.keySet());
        return names.entrySet().stream()
                .map(e -> {
                    ProcessStats s = stats.getOrDefault(e.getKey(), new ProcessStats(0.0, 0L));
                    return new JavaProcessInfo(e.getKey(), e.getValue(), s.cpu(), s.ramMb());
                })
                .sorted(Comparator.comparingLong(JavaProcessInfo::pid))
                .toList();
    }

    private Map<Long, String> javaProcessNames() {
        try {
            Process jps = new ProcessBuilder("jps", "-l")
                    .redirectErrorStream(true)
                    .start();
            return parseJpsOutput(new String(jps.getInputStream().readAllBytes()));
        } catch (Exception e) {
            log.warn("Failed to list Java processes via 'jps -l': {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, ProcessStats> readStats(Set<Long> pids) {
        try {
            String pidList = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
            Process ps = new ProcessBuilder(
                    "ps", "-p", pidList, "-o", "pid,%cpu,rss", "--no-headers")
                    .redirectErrorStream(true)
                    .start();
            return parsePsOutput(new String(ps.getInputStream().readAllBytes()));
        } catch (Exception e) {
            log.warn("Failed to read CPU/RAM stats via 'ps' for pids {}: {}", pids, e.getMessage());
            return Map.of();
        }
    }

    static Map<Long, String> parseJpsOutput(String output) {
        return output.lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\\s+", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(
                        parts -> Long.parseLong(parts[0]),
                        parts -> parts[1]
                ));
    }

    static Map<Long, ProcessStats> parsePsOutput(String output) {
        Map<Long, ProcessStats> result = new HashMap<>();
        if (output == null || output.isBlank()) return result;
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) continue;
            result.put(
                    Long.parseLong(parts[0]),
                    new ProcessStats(Double.parseDouble(parts[1]), Long.parseLong(parts[2]) / 1024));
        }
        return result;
    }
}
