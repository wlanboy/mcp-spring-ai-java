package com.example.helloworld;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class JavaProcessTools {

    record JavaProcessInfo(long pid, String name, double cpuPercent, long ramMb) {}

    record ProcessStats(double cpu, long ramMb) {}

    @Tool(description = "Lists all running Java processes with PID, name, CPU usage (%) and RAM usage (MB)")
    public List<JavaProcessInfo> listJavaProcesses() {
        return javaProcessNames().entrySet().stream()
                .map(e -> {
                    ProcessStats stats = readStats(e.getKey());
                    return new JavaProcessInfo(e.getKey(), e.getValue(), stats.cpu(), stats.ramMb());
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
            return Map.of();
        }
    }

    private ProcessStats readStats(long pid) {
        try {
            Process ps = new ProcessBuilder(
                    "ps", "-p", String.valueOf(pid), "-o", "%cpu,rss", "--no-headers")
                    .redirectErrorStream(true)
                    .start();
            return parsePsOutput(new String(ps.getInputStream().readAllBytes()));
        } catch (Exception e) {
            return new ProcessStats(0.0, 0L);
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

    static ProcessStats parsePsOutput(String output) {
        if (output == null || output.isBlank()) return new ProcessStats(0.0, 0L);
        String[] parts = output.trim().split("\\s+");
        return new ProcessStats(
                Double.parseDouble(parts[0]),
                Long.parseLong(parts[1]) / 1024
        );
    }
}
