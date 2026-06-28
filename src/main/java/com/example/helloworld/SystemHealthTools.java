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

    @Value("${health.threshold.swap.warning:70}")
    private double swapWarning;

    @Value("${health.threshold.swap.critical:90}")
    private double swapCritical;

    @Value("${health.threshold.disk.warning:80}")
    private double diskWarning;

    @Value("${health.threshold.disk.critical:90}")
    private double diskCritical;

    @Value("${health.threshold.temperature.warning:80}")
    private double tempWarningPercent;

    @Value("${health.threshold.temperature.critical:95}")
    private double tempCriticalPercent;

    @Value("${health.threshold.psi.io.full.warning:5}")
    private double psiIoFullWarning;

    @Value("${health.threshold.psi.io.full.critical:20}")
    private double psiIoFullCritical;

    @Value("${health.threshold.psi.memory.full.warning:1}")
    private double psiMemFullWarning;

    @Value("${health.threshold.psi.memory.full.critical:10}")
    private double psiMemFullCritical;

    @Value("${health.threshold.psi.cpu.some.warning:30}")
    private double psiCpuSomeWarning;

    @Value("${health.threshold.psi.cpu.some.critical:70}")
    private double psiCpuSomeCritical;

    public SystemHealthTools(NodeExporterTools nodeExporter) {
        this.nodeExporter = nodeExporter;
    }

    enum Severity { OK, WARNING, CRITICAL }

    record Anomaly(String metric, String value, Severity severity, String message) {}

    @Tool(description = "Checks CPU, memory, swap, disk space, system load, hardware temperatures and Linux PSI pressure for anomalies. Returns all findings with severity OK/WARNING/CRITICAL.")
    public List<Anomaly> getSystemAnomalies() throws Exception {
        List<Anomaly> results = new ArrayList<>();

        NodeExporterTools.MemoryStats mem = nodeExporter.getMemoryStats();
        results.add(check(
                "memory",
                "%.1f%% (%d/%d MB used)".formatted(mem.usedPercent(), mem.usedMb(), mem.totalMb()),
                mem.usedPercent(), memoryWarning, memoryCritical, "Memory usage"));

        NodeExporterTools.SwapStats swap = nodeExporter.getSwapStats();
        if (swap.totalMb() > 0) {
            results.add(check(
                    "swap",
                    "%.1f%% (%d/%d MB used)".formatted(swap.usedPercent(), swap.usedMb(), swap.totalMb()),
                    swap.usedPercent(), swapWarning, swapCritical, "Swap usage"));
        }

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

        results.addAll(checkDiskSpace());
        results.addAll(checkTemperatures());
        results.addAll(checkPressure());

        return results;
    }

    private List<Anomaly> checkDiskSpace() throws Exception {
        List<Anomaly> results = new ArrayList<>();
        for (NodeExporterTools.DiskSpace disk : nodeExporter.getDiskSpace()) {
            results.add(check(
                    "disk:" + disk.mountPoint(),
                    "%.1f%% (%d/%d GB used)".formatted(disk.usedPercent(), disk.usedGb(), disk.totalGb()),
                    disk.usedPercent(), diskWarning, diskCritical,
                    "Disk " + disk.mountPoint()));
        }
        if (results.isEmpty()) {
            results.add(new Anomaly("disk", "no real filesystems found", Severity.OK, "No real filesystems to check"));
        }
        return results;
    }

    private List<Anomaly> checkPressure() throws Exception {
        NodeExporterTools.PressureStats p = nodeExporter.getPressureStats();
        List<Anomaly> results = new ArrayList<>();

        results.add(check("psi:cpu:some",    "%.1f%%".formatted(p.cpuSomePercent()),
                p.cpuSomePercent(), psiCpuSomeWarning, psiCpuSomeCritical, "CPU pressure (some)"));
        results.add(check("psi:io:full",     "%.1f%%".formatted(p.ioFullPercent()),
                p.ioFullPercent(), psiIoFullWarning, psiIoFullCritical, "IO pressure (full)"));
        results.add(check("psi:memory:full", "%.1f%%".formatted(p.memoryFullPercent()),
                p.memoryFullPercent(), psiMemFullWarning, psiMemFullCritical, "Memory pressure (full)"));

        return results;
    }

    private List<Anomaly> checkTemperatures() throws Exception {
        List<Anomaly> results = new ArrayList<>();
        for (NodeExporterTools.Temperature t : nodeExporter.getTemperatures()) {
            if (t.critCelsius() <= 0) continue;
            double pct = t.celsius() / t.critCelsius() * 100;
            String value = "%.1f°C (crit: %.1f°C)".formatted(t.celsius(), t.critCelsius());
            String label = "%s/%s".formatted(t.chip(), t.sensor());
            if (pct >= tempCriticalPercent)
                results.add(new Anomaly("temperature:" + label, value, Severity.CRITICAL,
                        "Temperature critically high: %.1f°C is %.0f%% of critical threshold".formatted(t.celsius(), pct)));
            else if (pct >= tempWarningPercent)
                results.add(new Anomaly("temperature:" + label, value, Severity.WARNING,
                        "Temperature elevated: %.1f°C is %.0f%% of critical threshold".formatted(t.celsius(), pct)));
        }
        if (results.isEmpty())
            results.add(new Anomaly("temperature", "all sensors normal", Severity.OK, "All temperatures within safe range"));
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
