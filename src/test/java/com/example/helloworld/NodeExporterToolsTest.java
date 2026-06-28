package com.example.helloworld;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeExporterToolsTest {

    // ── parseMemory ────────────────────────────────────────────────────────

    @Test
    void parseMemory_computesUsageFromTotalsAndAvailable() {
        Map<String, Double> m = Map.of(
                "node_memory_MemTotal_bytes",     8_589_934_592.0,
                "node_memory_MemAvailable_bytes", 4_294_967_296.0);
        NodeExporterTools.MemoryStats s = NodeExporterTools.parseMemory(m);
        assertThat(s.totalMb()).isEqualTo(8192L);
        assertThat(s.availableMb()).isEqualTo(4096L);
        assertThat(s.usedMb()).isEqualTo(4096L);
        assertThat(s.usedPercent()).isEqualTo(50.0);
    }

    @Test
    void parseMemory_returnsZeroForEmptyMetrics() {
        NodeExporterTools.MemoryStats s = NodeExporterTools.parseMemory(Map.of());
        assertThat(s.totalMb()).isEqualTo(0L);
        assertThat(s.usedPercent()).isEqualTo(0.0);
    }

    // ── parseSwap ──────────────────────────────────────────────────────────

    @Test
    void parseSwap_computesSwapUsage() {
        Map<String, Double> m = Map.of(
                "node_memory_SwapTotal_bytes", 2_147_483_648.0,
                "node_memory_SwapFree_bytes",  1_073_741_824.0);
        NodeExporterTools.SwapStats s = NodeExporterTools.parseSwap(m);
        assertThat(s.totalMb()).isEqualTo(2048L);
        assertThat(s.freeMb()).isEqualTo(1024L);
        assertThat(s.usedMb()).isEqualTo(1024L);
        assertThat(s.usedPercent()).isEqualTo(50.0);
    }

    @Test
    void parseSwap_returnsZeroWhenNoSwap() {
        NodeExporterTools.SwapStats s = NodeExporterTools.parseSwap(Map.of());
        assertThat(s.totalMb()).isEqualTo(0L);
        assertThat(s.usedPercent()).isEqualTo(0.0);
    }

    // ── parseLoad ──────────────────────────────────────────────────────────

    @Test
    void parseLoad_extractsLoadAverages() {
        Map<String, Double> m = Map.of("node_load1", 1.5, "node_load5", 1.2, "node_load15", 0.8);
        NodeExporterTools.SystemLoad l = NodeExporterTools.parseLoad(m);
        assertThat(l.load1()).isEqualTo(1.5);
        assertThat(l.load5()).isEqualTo(1.2);
        assertThat(l.load15()).isEqualTo(0.8);
    }

    @Test
    void parseLoad_returnsZeroesForEmptyMetrics() {
        NodeExporterTools.SystemLoad l = NodeExporterTools.parseLoad(Map.of());
        assertThat(l.load1()).isEqualTo(0.0);
        assertThat(l.load5()).isEqualTo(0.0);
        assertThat(l.load15()).isEqualTo(0.0);
    }

    // ── parseUptime ────────────────────────────────────────────────────────

    @Test
    void parseUptime_computesHoursAndMinutes() {
        long boot = 1_000_000L;
        long now  = boot + 3 * 3600 + 30 * 60;
        assertThat(NodeExporterTools.parseUptime(Map.of("node_boot_time_seconds", (double) boot), now))
                .isEqualTo("3 hours 30 minutes");
    }

    @Test
    void parseUptime_returnsUnknownWhenBootTimeAbsent() {
        assertThat(NodeExporterTools.parseUptime(Map.of(), 0L)).isEqualTo("Unknown");
    }

    // ── parseNetwork ───────────────────────────────────────────────────────

    @Test
    void parseNetwork_extractsInterfaceTraffic() {
        String raw = """
                node_network_receive_bytes_total{device="eth0"} 1048576
                node_network_transmit_bytes_total{device="eth0"} 2097152
                """;
        List<NodeExporterTools.NetworkInterface> result = NodeExporterTools.parseNetwork(raw);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("eth0");
        assertThat(result.get(0).receivedMb()).isEqualTo(1L);
        assertThat(result.get(0).transmittedMb()).isEqualTo(2L);
    }

    @Test
    void parseNetwork_sortsByInterfaceName() {
        String raw = """
                node_network_receive_bytes_total{device="eth1"} 0
                node_network_transmit_bytes_total{device="eth1"} 0
                node_network_receive_bytes_total{device="eth0"} 0
                node_network_transmit_bytes_total{device="eth0"} 0
                """;
        assertThat(NodeExporterTools.parseNetwork(raw))
                .extracting(NodeExporterTools.NetworkInterface::name)
                .containsExactly("eth0", "eth1");
    }

    @Test
    void parseNetwork_returnsEmptyForBlankInput() {
        assertThat(NodeExporterTools.parseNetwork("")).isEmpty();
    }

    // ── parseNetworkErrors ─────────────────────────────────────────────────

    @Test
    void parseNetworkErrors_extractsAllCounters() {
        String raw = """
                node_network_receive_errs_total{device="eth0"} 5
                node_network_transmit_errs_total{device="eth0"} 3
                node_network_receive_drop_total{device="eth0"} 10
                node_network_transmit_drop_total{device="eth0"} 2
                """;
        List<NodeExporterTools.NetworkErrors> result = NodeExporterTools.parseNetworkErrors(raw);
        assertThat(result).hasSize(1);
        NodeExporterTools.NetworkErrors e = result.get(0);
        assertThat(e.iface()).isEqualTo("eth0");
        assertThat(e.receiveErrors()).isEqualTo(5L);
        assertThat(e.transmitErrors()).isEqualTo(3L);
        assertThat(e.receiveDrops()).isEqualTo(10L);
        assertThat(e.transmitDrops()).isEqualTo(2L);
    }

    // ── parseTemperatures ──────────────────────────────────────────────────

    @Test
    void parseTemperatures_extractsSensorReadings() {
        String raw = """
                node_hwmon_temp_celsius{chip="coretemp-isa-0000",sensor="temp1"} 45.0
                node_hwmon_temp_crit_celsius{chip="coretemp-isa-0000",sensor="temp1"} 100.0
                """;
        List<NodeExporterTools.Temperature> result = NodeExporterTools.parseTemperatures(raw);
        assertThat(result).hasSize(1);
        NodeExporterTools.Temperature t = result.get(0);
        assertThat(t.chip()).isEqualTo("coretemp-isa-0000");
        assertThat(t.sensor()).isEqualTo("temp1");
        assertThat(t.celsius()).isEqualTo(45.0);
        assertThat(t.critCelsius()).isEqualTo(100.0);
    }

    @Test
    void parseTemperatures_filtersZeroCelsius() {
        String raw = "node_hwmon_temp_celsius{chip=\"c\",sensor=\"s\"} 0.0\n";
        assertThat(NodeExporterTools.parseTemperatures(raw)).isEmpty();
    }

    @Test
    void parseTemperatures_sortedByTemperatureDescending() {
        String raw = """
                node_hwmon_temp_celsius{chip="c",sensor="a"} 30.0
                node_hwmon_temp_crit_celsius{chip="c",sensor="a"} 100.0
                node_hwmon_temp_celsius{chip="c",sensor="b"} 60.0
                node_hwmon_temp_crit_celsius{chip="c",sensor="b"} 100.0
                """;
        assertThat(NodeExporterTools.parseTemperatures(raw))
                .extracting(NodeExporterTools.Temperature::celsius)
                .containsExactly(60.0, 30.0);
    }

    // ── parseDiskSpace ─────────────────────────────────────────────────────

    @Test
    void parseDiskSpace_extractsMountPoints() {
        String raw = """
                node_filesystem_size_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 107374182400
                node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 53687091200
                """;
        List<NodeExporterTools.DiskSpace> result = NodeExporterTools.parseDiskSpace(raw);
        assertThat(result).hasSize(1);
        NodeExporterTools.DiskSpace d = result.get(0);
        assertThat(d.mountPoint()).isEqualTo("/");
        assertThat(d.device()).isEqualTo("/dev/sda1");
        assertThat(d.fsType()).isEqualTo("ext4");
        assertThat(d.totalGb()).isEqualTo(100L);
        assertThat(d.availGb()).isEqualTo(50L);
        assertThat(d.usedGb()).isEqualTo(50L);
        assertThat(d.usedPercent()).isEqualTo(50.0);
    }

    @Test
    void parseDiskSpace_filtersVirtualFilesystems() {
        String raw = """
                node_filesystem_size_bytes{device="proc",fstype="proc",mountpoint="/proc"} 0
                node_filesystem_avail_bytes{device="proc",fstype="proc",mountpoint="/proc"} 0
                node_filesystem_size_bytes{device="sysfs",fstype="sysfs",mountpoint="/sys"} 0
                node_filesystem_avail_bytes{device="sysfs",fstype="sysfs",mountpoint="/sys"} 0
                """;
        assertThat(NodeExporterTools.parseDiskSpace(raw)).isEmpty();
    }

    @Test
    void parseDiskSpace_filtersZeroSizeFilesystems() {
        String raw = """
                node_filesystem_size_bytes{device="tmpfs",fstype="tmpfs",mountpoint="/run"} 0
                node_filesystem_avail_bytes{device="tmpfs",fstype="tmpfs",mountpoint="/run"} 0
                """;
        assertThat(NodeExporterTools.parseDiskSpace(raw)).isEmpty();
    }

    @Test
    void parseDiskSpace_sortsByMountPoint() {
        String raw = """
                node_filesystem_size_bytes{device="/dev/sdb1",fstype="ext4",mountpoint="/data"} 107374182400
                node_filesystem_avail_bytes{device="/dev/sdb1",fstype="ext4",mountpoint="/data"} 53687091200
                node_filesystem_size_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 107374182400
                node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 53687091200
                """;
        assertThat(NodeExporterTools.parseDiskSpace(raw))
                .extracting(NodeExporterTools.DiskSpace::mountPoint)
                .containsExactly("/", "/data");
    }

    // ── parseSystemInfo ────────────────────────────────────────────────────

    @Test
    void parseSystemInfo_extractsUnameLabels() {
        String raw = "node_uname_info{domainname=\"(none)\",machine=\"x86_64\",nodename=\"myserver\"," +
                "release=\"5.15.0-1034\",sysname=\"Linux\",version=\"#38-Ubuntu\"} 1\n";
        NodeExporterTools.SystemInfo info = NodeExporterTools.parseSystemInfo(raw);
        assertThat(info.hostname()).isEqualTo("myserver");
        assertThat(info.kernel()).isEqualTo("5.15.0-1034");
        assertThat(info.os()).isEqualTo("Linux");
        assertThat(info.machine()).isEqualTo("x86_64");
    }

    @Test
    void parseSystemInfo_returnsUnknownWhenMissing() {
        NodeExporterTools.SystemInfo info = NodeExporterTools.parseSystemInfo("");
        assertThat(info.hostname()).isEqualTo("unknown");
        assertThat(info.kernel()).isEqualTo("unknown");
        assertThat(info.os()).isEqualTo("unknown");
        assertThat(info.machine()).isEqualTo("unknown");
    }
}
