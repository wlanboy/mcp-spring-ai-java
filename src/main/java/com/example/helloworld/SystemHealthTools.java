package com.example.helloworld;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthTools {

    private final NodeExporterTools nodeExporter;

    @Value("${health.threshold.cpu.warning:70}")
    private double cpuWarning;

    @Value("${health.threshold.cpu.critical:90}")
    private double cpuCritical;

    @Value("${health.threshold.memory.warning:80}")
    private double memoryWarning;

    @Value("${health.threshold.memory.critical:90}")
    private double memoryCritical;

    public SystemHealthTools(NodeExporterTools nodeExporter) {
        this.nodeExporter = nodeExporter;
    }

    enum Severity { OK, WARNING, CRITICAL }

    record Anomaly(String metric, String value, Severity severity, String message) {}

    @Tool(description = "Checks CPU, memory and system load for anomalies. Returns all findings with severity OK/WARNING/CRITICAL.")
    public List<Anomaly> getSystemAnomalies() throws Exception {
        List<Anomaly> results = new ArrayList<>();

        NodeExporterTools.MemoryStats mem = nodeExporter.getMemoryStats();
        results.add(check(
                "memory",
                "%.1f%% (%d/%d MB used)".formatted(mem.usedPercent(), mem.usedMb(), mem.totalMb()),
                mem.usedPercent(), memoryWarning, memoryCritical, "Memory usage"));

        NodeExporterTools.CpuUsage cpu = nodeExporter.getCpuUsage();
        results.add(check(
                "cpu",
                "%.1f%% (user %.1f%%, system %.1f%%)".formatted(cpu.totalUsedPercent(), cpu.userPercent(), cpu.systemPercent()),
                cpu.totalUsedPercent(), cpuWarning, cpuCritical, "CPU usage"));

        NodeExporterTools.SystemLoad load = nodeExporter.getSystemLoad();
        int cores = Runtime.getRuntime().availableProcessors();
        results.add(checkLoad(
                "load",
                "%.2f / %.2f / %.2f (cores: %d)".formatted(load.load1(), load.load5(), load.load15(), cores),
                load.load1(), cores));

        return results;
    }

    private Anomaly check(String metric, String value, double current,
                          double warning, double critical, String label) {
        if (current >= critical)
            return new Anomaly(metric, value, Severity.CRITICAL,
                    "%s critically high: %.1f%%".formatted(label, current));
        if (current >= warning)
            return new Anomaly(metric, value, Severity.WARNING,
                    "%s elevated: %.1f%%".formatted(label, current));
        return new Anomaly(metric, value, Severity.OK, "%s is normal".formatted(label));
    }

    private Anomaly checkLoad(String metric, String value, double load1, int cores) {
        if (load1 >= cores * 2.0)
            return new Anomaly(metric, value, Severity.CRITICAL,
                    "System severely overloaded: load %.2f with %d cores".formatted(load1, cores));
        if (load1 >= cores)
            return new Anomaly(metric, value, Severity.WARNING,
                    "Load exceeds core count: %.2f > %d".formatted(load1, cores));
        return new Anomaly(metric, value, Severity.OK, "System load is normal");
    }
}
