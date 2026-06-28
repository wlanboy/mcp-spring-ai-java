package com.example.helloworld;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemHealthToolsTest {

    @Mock
    private NodeExporterTools nodeExporter;

    @InjectMocks
    private SystemHealthTools systemHealth;

    @BeforeEach
    void setThresholds() {
        ReflectionTestUtils.setField(systemHealth, "cpuWarning",       70.0);
        ReflectionTestUtils.setField(systemHealth, "cpuCritical",      90.0);
        ReflectionTestUtils.setField(systemHealth, "memoryWarning",    80.0);
        ReflectionTestUtils.setField(systemHealth, "memoryCritical",   90.0);
        ReflectionTestUtils.setField(systemHealth, "swapWarning",      70.0);
        ReflectionTestUtils.setField(systemHealth, "swapCritical",     90.0);
        ReflectionTestUtils.setField(systemHealth, "diskWarning",      80.0);
        ReflectionTestUtils.setField(systemHealth, "diskCritical",     90.0);
        ReflectionTestUtils.setField(systemHealth, "tempWarningPercent",  80.0);
        ReflectionTestUtils.setField(systemHealth, "tempCriticalPercent", 95.0);
        ReflectionTestUtils.setField(systemHealth, "psiIoFullWarning",    5.0);
        ReflectionTestUtils.setField(systemHealth, "psiIoFullCritical",  20.0);
        ReflectionTestUtils.setField(systemHealth, "psiMemFullWarning",   1.0);
        ReflectionTestUtils.setField(systemHealth, "psiMemFullCritical", 10.0);
        ReflectionTestUtils.setField(systemHealth, "psiCpuSomeWarning",  30.0);
        ReflectionTestUtils.setField(systemHealth, "psiCpuSomeCritical", 70.0);
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private void stubAllOk() throws Exception {
        when(nodeExporter.getMemoryStats())
                .thenReturn(new NodeExporterTools.MemoryStats(8192, 4096, 4096, 50.0));
        when(nodeExporter.getSwapStats())
                .thenReturn(new NodeExporterTools.SwapStats(2048, 1024, 1024, 50.0));
        when(nodeExporter.getCpuUsage())
                .thenReturn(new NodeExporterTools.CpuUsage(30.0, 10.0, 60.0, 40.0));
        when(nodeExporter.getSystemLoad())
                .thenReturn(new NodeExporterTools.SystemLoad(0.5, 0.4, 0.3));
        when(nodeExporter.getDiskSpace())
                .thenReturn(List.of(new NodeExporterTools.DiskSpace("/", "/dev/sda1", "ext4", 100, 50, 50, 50.0)));
        when(nodeExporter.getTemperatures()).thenReturn(List.of());
        when(nodeExporter.getPressureStats())
                .thenReturn(new NodeExporterTools.PressureStats(0.0, 0.0, 0.0, 0.0, 0.0));
    }

    // ── All-OK path ────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_allOkWhenEverythingNormal() throws Exception {
        stubAllOk();
        List<SystemHealthTools.Anomaly> result = systemHealth.getSystemAnomalies();
        assertThat(result).allSatisfy(a ->
                assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK));
    }

    // ── Memory ────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_memoryCriticalAtThreshold() throws Exception {
        stubAllOk();
        when(nodeExporter.getMemoryStats())
                .thenReturn(new NodeExporterTools.MemoryStats(8192, 0, 8192, 100.0));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("memory");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_memoryWarningBetweenThresholds() throws Exception {
        stubAllOk();
        when(nodeExporter.getMemoryStats())
                .thenReturn(new NodeExporterTools.MemoryStats(8192, 1638, 6554, 85.0));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("memory");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    // ── Swap ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_swapCriticalWhenHighUsage() throws Exception {
        stubAllOk();
        when(nodeExporter.getSwapStats())
                .thenReturn(new NodeExporterTools.SwapStats(2048, 100, 1948, 95.0));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("swap");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_swapSkippedWhenNoSwapPartition() throws Exception {
        stubAllOk();
        when(nodeExporter.getSwapStats())
                .thenReturn(new NodeExporterTools.SwapStats(0, 0, 0, 0.0));

        assertThat(systemHealth.getSystemAnomalies())
                .noneMatch(a -> a.metric().equals("swap"));
    }

    // ── CPU ───────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_cpuWarningBetweenThresholds() throws Exception {
        stubAllOk();
        when(nodeExporter.getCpuUsage())
                .thenReturn(new NodeExporterTools.CpuUsage(70.0, 10.0, 20.0, 80.0));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("cpu");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    // ── Load ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_loadCriticalWhenDoubleCores() throws Exception {
        stubAllOk();
        int cores = Runtime.getRuntime().availableProcessors();
        when(nodeExporter.getSystemLoad())
                .thenReturn(new NodeExporterTools.SystemLoad(cores * 2.5, cores * 2.0, cores * 1.5));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_loadWarningWhenExceedsCoreCount() throws Exception {
        stubAllOk();
        int cores = Runtime.getRuntime().availableProcessors();
        when(nodeExporter.getSystemLoad())
                .thenReturn(new NodeExporterTools.SystemLoad(cores * 1.5, cores, cores * 0.8));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("load");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING);
        });
    }

    // ── Disk ──────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_diskCriticalWhenNearlyFull() throws Exception {
        stubAllOk();
        when(nodeExporter.getDiskSpace())
                .thenReturn(List.of(new NodeExporterTools.DiskSpace("/", "/dev/sda1", "ext4", 100, 5, 95, 95.0)));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("disk:/");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_diskWarningForEachOverloadedMount() throws Exception {
        stubAllOk();
        when(nodeExporter.getDiskSpace()).thenReturn(List.of(
                new NodeExporterTools.DiskSpace("/",     "/dev/sda1", "ext4", 100, 15, 85, 85.0),
                new NodeExporterTools.DiskSpace("/data", "/dev/sdb1", "ext4", 200, 30, 170, 85.0)));

        List<SystemHealthTools.Anomaly> result = systemHealth.getSystemAnomalies();
        assertThat(result).anySatisfy(a -> assertThat(a.metric()).isEqualTo("disk:/"));
        assertThat(result).anySatisfy(a -> assertThat(a.metric()).isEqualTo("disk:/data"));
        assertThat(result)
                .filteredOn(a -> a.metric().startsWith("disk:"))
                .allSatisfy(a -> assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.WARNING));
    }

    @Test
    void getSystemAnomalies_diskOkWhenNoDiskSpaceReturned() throws Exception {
        stubAllOk();
        when(nodeExporter.getDiskSpace()).thenReturn(List.of());

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("disk");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK);
        });
    }

    // ── Temperature ───────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_temperatureCriticalNearSensorLimit() throws Exception {
        stubAllOk();
        when(nodeExporter.getTemperatures())
                .thenReturn(List.of(new NodeExporterTools.Temperature("coretemp", "temp1", 96.0, 100.0)));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).startsWith("temperature:");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }

    @Test
    void getSystemAnomalies_temperatureOkWhenNoSensors() throws Exception {
        stubAllOk();
        when(nodeExporter.getTemperatures()).thenReturn(List.of());

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("temperature");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.OK);
        });
    }

    // ── PSI ───────────────────────────────────────────────────────────────

    @Test
    void getSystemAnomalies_psiIoCriticalUnderHighIoLoad() throws Exception {
        stubAllOk();
        when(nodeExporter.getPressureStats())
                .thenReturn(new NodeExporterTools.PressureStats(0.0, 0.0, 25.0, 0.0, 0.0));

        assertThat(systemHealth.getSystemAnomalies()).anySatisfy(a -> {
            assertThat(a.metric()).isEqualTo("psi:io:full");
            assertThat(a.severity()).isEqualTo(SystemHealthTools.Severity.CRITICAL);
        });
    }
}
