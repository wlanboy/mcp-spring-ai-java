package com.example.helloworld;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaProcessToolsTest {

    private JavaProcessTools tools;

    @BeforeEach
    void setUp() {
        tools = new JavaProcessTools();
    }

    // ── parseJpsOutput ─────────────────────────────────────────────────────

    @Test
    void parseJpsOutput_parsesMultipleLines() {
        String output = """
                198070 org.codehaus.plexus.classworlds.launcher.Launcher
                198149 com.example.helloworld.HelloworldApplication
                """;
        Map<Long, String> result = JavaProcessTools.parseJpsOutput(output);
        assertThat(result)
                .hasSize(2)
                .containsEntry(198070L, "org.codehaus.plexus.classworlds.launcher.Launcher")
                .containsEntry(198149L, "com.example.helloworld.HelloworldApplication");
    }

    @Test
    void parseJpsOutput_ignoresBlankLines() {
        String output = "\n12345 com.example.Main\n\n";
        assertThat(JavaProcessTools.parseJpsOutput(output)).hasSize(1);
    }

    @Test
    void parseJpsOutput_returnsEmptyMapForEmptyInput() {
        assertThat(JavaProcessTools.parseJpsOutput("")).isEmpty();
        assertThat(JavaProcessTools.parseJpsOutput("   \n  ")).isEmpty();
    }

    @Test
    void parseJpsOutput_handlesJarPaths() {
        String output = "178935 /home/user/.vscode/extensions/redhat.java/launcher.jar\n";
        Map<Long, String> result = JavaProcessTools.parseJpsOutput(output);
        assertThat(result).containsEntry(178935L, "/home/user/.vscode/extensions/redhat.java/launcher.jar");
    }

    // ── parsePsOutput ──────────────────────────────────────────────────────

    @Test
    void parsePsOutput_parsesCpuAndConvertskbToMb() {
        JavaProcessTools.ProcessStats stats = JavaProcessTools.parsePsOutput(" 5.2 204800");
        assertThat(stats.cpu()).isEqualTo(5.2);
        assertThat(stats.ramMb()).isEqualTo(200L);
    }

    @Test
    void parsePsOutput_returnsZeroForEmptyOutput() {
        JavaProcessTools.ProcessStats stats = JavaProcessTools.parsePsOutput("");
        assertThat(stats.cpu()).isEqualTo(0.0);
        assertThat(stats.ramMb()).isEqualTo(0L);
    }

    @Test
    void parsePsOutput_returnsZeroForNullOutput() {
        JavaProcessTools.ProcessStats stats = JavaProcessTools.parsePsOutput(null);
        assertThat(stats.cpu()).isEqualTo(0.0);
        assertThat(stats.ramMb()).isEqualTo(0L);
    }

    @Test
    void parsePsOutput_handlesZeroCpu() {
        JavaProcessTools.ProcessStats stats = JavaProcessTools.parsePsOutput(" 0.0 1024");
        assertThat(stats.cpu()).isEqualTo(0.0);
        assertThat(stats.ramMb()).isEqualTo(1L);
    }

    // ── listJavaProcesses (Integration) ───────────────────────────────────

    @Test
    void listJavaProcesses_containsCurrentJvm() {
        long currentPid = ProcessHandle.current().pid();
        List<JavaProcessTools.JavaProcessInfo> processes = tools.listJavaProcesses();

        assertThat(processes).anySatisfy(p -> assertThat(p.pid()).isEqualTo(currentPid));
    }

    @Test
    void listJavaProcesses_isSortedByPid() {
        List<Long> pids = tools.listJavaProcesses().stream()
                .map(JavaProcessTools.JavaProcessInfo::pid)
                .toList();
        assertThat(pids).isSorted();
    }

    @Test
    void listJavaProcesses_allEntriesHaveNonBlankName() {
        assertThat(tools.listJavaProcesses())
                .allSatisfy(p -> assertThat(p.name()).isNotBlank());
    }

    @Test
    void listJavaProcesses_allEntriesHaveNonNegativeStats() {
        assertThat(tools.listJavaProcesses()).allSatisfy(p -> {
            assertThat(p.cpuPercent()).isGreaterThanOrEqualTo(0.0);
            assertThat(p.ramMb()).isGreaterThanOrEqualTo(0L);
        });
    }
}
