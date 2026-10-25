package com.example.helloworld;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemHealthToolsTest {

    @Mock
    private NodeExporterTools nodeExporter;

    // Progress/logging are void calls; a plain mock no-ops them, which is all these tests need.
    @Mock
    private McpSyncRequestContext ctx;

    private SystemHealthTools systemHealth;

    // Host core count as reported by node_exporter - deliberately fixed and independent of
    // the test JVM's availableProcessors().
    private static final int CORES = 4;

    @BeforeEach
    void setUp() {
        // HealthThresholds defaults: cpu 70/90, memory 80/90, swap 70/90, disk 80/90,
        // temperature 80/95, psi cpu.some 30/70, io.full 5/20, memory.full 1/10
        systemHealth = new SystemHealthTools(nodeExporter, new HealthThresholds());
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static final NodeExporterTools.MemoryStats OK_MEMORY =
            new NodeExporterTools.MemoryStats(8192, 4096, 4096, 50.0);
    private static final NodeExporterTools.SwapStats OK_SWAP =
            new NodeExporterTools.SwapStats(2048, 1024, 1024, 50.0);
    private static final NodeExporterTools.CpuUsage OK_CPU =
            new NodeExporterTools.CpuUsage(30.0, 10.0, 60.0, 40.0);
    private static final NodeExporterTools.SystemLoad OK_LOAD =
            new NodeExporterTools.SystemLoad(0.5, 0.4, 0.3);
    private static final List<NodeExporterTools.DiskSpace> OK_DISKS =
            List.of(new NodeExporterTools.DiskSpace("/", "/dev/sda1", "ext4", 100, 50, 50, 50.0));
    private static final List<NodeExporterTools.Temperature> OK_TEMPERATURES = List.of();
    private static final NodeExporterTools.PressureStats OK_PRESSURE =
            new NodeExporterTools.PressureStats(0.0, 0.0, 0.0, 0.0, 0.0);

    private void stub(NodeExporterTools.MemoryStats memory, NodeExporterTools.SwapStats swap,
                       NodeExporterTools.CpuUsage cpu, NodeExporterTools.SystemLoad load,
                       List<NodeExporterTools.DiskSpace> disks, List<NodeExporterTools.Temperature> temperatures,
                       NodeExporterTools.PressureStats pressure) {
        when(nodeExporter.getHealthSnapshot()).thenReturn(
                new NodeExporterTools.HealthSnapshot(memory, swap, cpu, load, CORES, disks, temperatures, pressure));
    }

    private void stubAllOk() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);
    }

    // ── All-OK path ────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_allOkWhenEverythingNormal() {
        stubAllOk();
        List<SystemHealthTools.Anomaly> result = systemHealth.getSystemAnomalies(ctx).findings();
        assertThat(result).allSatisfy(a ->
                assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK));
        assertThat(systemHealth.getSystemAnomalies(ctx).overall()).isEqualTo(SystemHealthTools.Severity.OK);
    }

    // ── Overall severity ──────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_overallIsWorstFindingSeverity() {
        // memory WARNING + disk CRITICAL -> overall CRITICAL
        stub(new NodeExporterTools.MemoryStats(8192, 1638, 6554, 85.0),
                OK_SWAP, OK_CPU, OK_LOAD,
                List.of(new NodeExporterTools.DiskSpace("/", "/dev/sda1", "ext4", 100, 5, 95, 95.0)),
                OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).overall()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
    }

    @Test
    void getSystemAnomalies_overallWarningWhenOnlyWarnings() {
        stub(new NodeExporterTools.MemoryStats(8192, 1638, 6554, 85.0),
                OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).overall()).isEqualTo(SystemHealthTools.Severity.WARNING);
    }

    // ── Memory ────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_memoryCriticalAtThreshold() {
        stub(new NodeExporterTools.MemoryStats(8192, 0, 8192, 100.0),
                OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("memory");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_memoryWarningBetweenThresholds() {
        stub(new NodeExporterTools.MemoryStats(8192, 1638, 6554, 85.0),
                OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("memory");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    // ── Swap ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_swapCriticalWhenHighUsage() {
        stub(OK_MEMORY, new NodeExporterTools.SwapStats(2048, 100, 1948, 95.0),
                OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("swap");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_swapSkippedWhenNoSwapPartition() {
        stub(OK_MEMORY, new NodeExporterTools.SwapStats(0, 0, 0, 0.0),
                OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings())
                .noneMatch(a -> a.metric().equals("swap"));
    }

    // ── CPU ───────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_cpuWarningBetweenThresholds() {
        stub(OK_MEMORY, OK_SWAP, new NodeExporterTools.CpuUsage(70.0, 10.0, 20.0, 80.0),
                OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("cpu");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    // ── Load ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_loadCriticalWhenDoubleCores() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU,
                new NodeExporterTools.SystemLoad(CORES * 2.5, CORES * 2.0, CORES * 1.5),
                OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_loadWarningWhenExceedsCoreCount() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU,
                new NodeExporterTools.SystemLoad(CORES * 1.5, CORES, CORES * 0.8),
                OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    @Test
    void getSystemAnomalies_loadUsesHostCoreCountFromNodeExporter() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.value()).endsWith("(cores: " + CORES + ")");
        });
    }

    @Test
    void getSystemAnomalies_loadFallsBackToJvmCoresWhenNodeExporterHasNoCpuMetrics() {
        when(nodeExporter.getHealthSnapshot()).thenReturn(new NodeExporterTools.HealthSnapshot(
                OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, 0, OK_DISKS, OK_TEMPERATURES, OK_PRESSURE));
        int jvmCores = Runtime.getRuntime().availableProcessors();

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.value()).endsWith("(cores: " + jvmCores + ")");
        });
    }

    // ── Formatting ────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_formatsNumbersLocaleIndependent() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            stubAllOk();
            assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
                assertThat(a.metric()).isEqualTo("memory");
                assertThat(a.value()).isEqualTo("50.0% (4096/8192 MB used)");
            });
        } finally {
            Locale.setDefault(previous);
        }
    }

    // ── Disk ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_diskCriticalWhenNearlyFull() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD,
                List.of(new NodeExporterTools.DiskSpace("/", "/dev/sda1", "ext4", 100, 5, 95, 95.0)),
                OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("disk:/");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_diskWarningForEachOverloadedMount() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD,
                List.of(
                        new NodeExporterTools.DiskSpace("/",     "/dev/sda1", "ext4", 100, 15, 85, 85.0),
                        new NodeExporterTools.DiskSpace("/data", "/dev/sdb1", "ext4", 200, 30, 170, 85.0)),
                OK_TEMPERATURES, OK_PRESSURE);

        List<SystemHealthTools.Anomaly> result = systemHealth.getSystemAnomalies(ctx).findings();
        assertThat(result).anySatisfy(a -> assertThat(a.metric()).isEqualTo("disk:/"));
        assertThat(result).anySatisfy(a -> assertThat(a.metric()).isEqualTo("disk:/data"));
        assertThat(result)
                .filteredOn(a -> a.metric().startsWith("disk:"))
                .allSatisfy(a -> assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING));
    }

    @Test
    void getSystemAnomalies_diskOkWhenNoDiskSpaceReturned() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, List.of(), OK_TEMPERATURES, OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("disk");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK);
        });
    }

    // ── Temperature ───────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_temperatureCriticalNearSensorLimit() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS,
                List.of(new NodeExporterTools.Temperature("coretemp", "temp1", 96.0, 100.0)),
                OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).startsWith("temperature:");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_temperatureOkWhenNoSensors() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, List.of(), OK_PRESSURE);

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("temperature");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK);
        });
    }

    // ── PSI ───────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_psiIoCriticalUnderHighIoLoad() {
        stub(OK_MEMORY, OK_SWAP, OK_CPU, OK_LOAD, OK_DISKS, OK_TEMPERATURES,
                new NodeExporterTools.PressureStats(0.0, 0.0, 25.0, 0.0, 0.0));

        assertThat(systemHealth.getSystemAnomalies(ctx).findings()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("psi:io:full");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }
}
