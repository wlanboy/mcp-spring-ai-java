package com.example.helloworld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Service;

@Service
public class SystemHealthTools {

    private final NodeExporterTools nodeExporter;

    private final HealthThresholds thresholds;

    public SystemHealthTools(NodeExporterTools nodeExporter, HealthThresholds thresholds) {
        this.nodeExporter = nodeExporter;
        this.thresholds = thresholds;
    }

    enum Severity { OK, WARNING, CRITICAL }

    record Anomaly(String metric, String value, Severity severity, String message) {}

    // Wrapper object (MCP structured output must be a JSON object, not an array) that also
    // carries the worst severity, so the LLM sees the overall verdict without scanning all findings.
    record HealthReport(Severity overall, List<Anomaly> findings) {}

    // Read-only aggregated health check: readOnlyHint/idempotentHint = true,
    // destructiveHint = false, openWorldHint = false.
    @McpTool(title = "System Health Check", generateOutputSchema = true,
            description = "Checks CPU, memory, swap, disk space, system load, hardware temperatures and Linux PSI pressure for anomalies. Returns the overall severity plus all findings with severity OK/WARNING/CRITICAL.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public HealthReport getSystemAnomalies(McpSyncRequestContext ctx) {
        List<Anomaly> results = new ArrayList<>();

        // getHealthSnapshot() itself blocks for ~1s (before/after metrics sample) - report
        // that as progress since this is the longest-running tool in the server.
        ctx.info("Collecting a full health snapshot (memory, swap, CPU, load, disk, temperature, PSI)");
        ctx.progress(p -> p.progress(0).total(1.0).message("Collecting health snapshot"));
        NodeExporterTools.HealthSnapshot snap = nodeExporter.getHealthSnapshot();
        ctx.progress(p -> p.progress(1.0).total(1.0).message("Done"));

        NodeExporterTools.MemoryStats mem = snap.memory();
        results.add(check(
                "memory",
                fmt("%.1f%% (%d/%d MB used)", mem.usedPercent(), mem.usedMb(), mem.totalMb()),
                mem.usedPercent(), thresholds.getMemory(), "Memory usage"));

        NodeExporterTools.SwapStats swap = snap.swap();
        if (swap.totalMb() > 0) {
            results.add(check(
                    "swap",
                    fmt("%.1f%% (%d/%d MB used)", swap.usedPercent(), swap.usedMb(), swap.totalMb()),
                    swap.usedPercent(), thresholds.getSwap(), "Swap usage"));
        }

        NodeExporterTools.CpuUsage cpu = snap.cpu();
        results.add(check(
                "cpu",
                fmt("%.1f%% (user %.1f%%, system %.1f%%)", cpu.totalUsedPercent(), cpu.userPercent(), cpu.systemPercent()),
                cpu.totalUsedPercent(), thresholds.getCpu(), "CPU usage"));

        NodeExporterTools.SystemLoad load = snap.load();
        // Load average is a host metric, so compare it against the host's core count from
        // node_exporter - availableProcessors() is the container's view when running in Docker.
        // It is only the fallback if node_exporter exposes no per-CPU metrics.
        int cores = snap.cpuCores() > 0 ? snap.cpuCores() : Runtime.getRuntime().availableProcessors();
        results.add(checkLoad(
                "load",
                fmt("%.2f / %.2f / %.2f (cores: %d)", load.load1(), load.load5(), load.load15(), cores),
                load.load1(), cores));

        results.addAll(checkDiskSpace(snap.diskSpace()));
        results.addAll(checkTemperatures(snap.temperatures()));
        results.addAll(checkPressure(snap.pressure()));

        Severity overall = results.stream()
                .map(Anomaly::severity)
                .max(Comparator.naturalOrder())
                .orElse(Severity.OK);
        return new HealthReport(overall, results);
    }

    private List<Anomaly> checkDiskSpace(List<NodeExporterTools.DiskSpace> disks) {
        List<Anomaly> results = new ArrayList<>();
        for (NodeExporterTools.DiskSpace disk : disks) {
            results.add(check(
                    "disk:" + disk.mountPoint(),
                    fmt("%.1f%% (%d/%d GB used)", disk.usedPercent(), disk.usedGb(), disk.totalGb()),
                    disk.usedPercent(), thresholds.getDisk(),
                    "Disk " + disk.mountPoint()));
        }
        if (results.isEmpty()) {
            results.add(new Anomaly("disk", "no real filesystems found", Severity.OK, "No real filesystems to check"));
        }
        return results;
    }

    private List<Anomaly> checkPressure(NodeExporterTools.PressureStats p) {
        List<Anomaly> results = new ArrayList<>();
        HealthThresholds.Psi psi = thresholds.getPsi();

        results.add(check("psi:cpu:some",    fmt("%.1f%%", p.cpuSomePercent()),
                p.cpuSomePercent(), psi.getCpu().getSome(), "CPU pressure (some)"));
        results.add(check("psi:io:full",     fmt("%.1f%%", p.ioFullPercent()),
                p.ioFullPercent(), psi.getIo().getFull(), "IO pressure (full)"));
        results.add(check("psi:memory:full", fmt("%.1f%%", p.memoryFullPercent()),
                p.memoryFullPercent(), psi.getMemory().getFull(), "Memory pressure (full)"));

        return results;
    }

    private List<Anomaly> checkTemperatures(List<NodeExporterTools.Temperature> temperatures) {
        List<Anomaly> results = new ArrayList<>();
        HealthThresholds.Limits limits = thresholds.getTemperature();
        for (NodeExporterTools.Temperature t : temperatures) {
            if (t.critCelsius() <= 0) continue;
            double pct = t.celsius() / t.critCelsius() * 100;
            String value = fmt("%.1f°C (crit: %.1f°C)", t.celsius(), t.critCelsius());
            String label = "%s/%s".formatted(t.chip(), t.sensor());
            if (pct >= limits.getCritical())
                results.add(new Anomaly("temperature:" + label, value, Severity.CRITICAL,
                        fmt("Temperature critically high: %.1f°C is %.0f%% of critical threshold", t.celsius(), pct)));
            else if (pct >= limits.getWarning())
                results.add(new Anomaly("temperature:" + label, value, Severity.WARNING,
                        fmt("Temperature elevated: %.1f°C is %.0f%% of critical threshold", t.celsius(), pct)));
        }
        if (results.isEmpty())
            results.add(new Anomaly("temperature", "all sensors normal", Severity.OK, "All temperatures within safe range"));
        return results;
    }

    private Anomaly check(String metric, String value, double current,
                          HealthThresholds.Limits limits, String label) {
        if (current >= limits.getCritical())
            return new Anomaly(metric, value, Severity.CRITICAL,
                    fmt("%s critically high: %.1f%%", label, current));
        if (current >= limits.getWarning())
            return new Anomaly(metric, value, Severity.WARNING,
                    fmt("%s elevated: %.1f%%", label, current));
        return new Anomaly(metric, value, Severity.OK, "%s is normal".formatted(label));
    }

    private Anomaly checkLoad(String metric, String value, double load1, int cores) {
        if (load1 >= cores * 2.0)
            return new Anomaly(metric, value, Severity.CRITICAL,
                    fmt("System severely overloaded: load %.2f with %d cores", load1, cores));
        if (load1 >= cores)
            return new Anomaly(metric, value, Severity.WARNING,
                    fmt("Load exceeds core count: %.2f > %d", load1, cores));
        return new Anomaly(metric, value, Severity.OK, "System load is normal");
    }

    // Locale.ROOT: String.formatted() uses the JVM's default locale, which would render
    // "50,0%" on a German system - the LLM and any parser should always see "50.0%".
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
