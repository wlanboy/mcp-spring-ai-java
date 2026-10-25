package com.example.helloworld;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthThresholdsTest {

    private static HealthThresholds bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("health.threshold", HealthThresholds.class);
    }

    @Test
    void defaultsApplyWithoutAnyProperties() {
        HealthThresholds t = bind(Map.of());
        assertThat(t.getCpu().getWarning()).isEqualTo(70);
        assertThat(t.getCpu().getCritical()).isEqualTo(90);
        assertThat(t.getMemory().getWarning()).isEqualTo(80);
        assertThat(t.getTemperature().getCritical()).isEqualTo(95);
        assertThat(t.getPsi().getCpu().getSome().getWarning()).isEqualTo(30);
        assertThat(t.getPsi().getIo().getFull().getCritical()).isEqualTo(20);
        assertThat(t.getPsi().getMemory().getFull().getWarning()).isEqualTo(1);
    }

    @Test
    void overridingOneValueKeepsTheOtherDefaults() {
        HealthThresholds t = bind(Map.of("health.threshold.cpu.warning", "50"));
        assertThat(t.getCpu().getWarning()).isEqualTo(50);
        assertThat(t.getCpu().getCritical()).isEqualTo(90);
        assertThat(t.getMemory().getWarning()).isEqualTo(80);
    }

    @Test
    void bindsNestedPsiKeys() {
        HealthThresholds t = bind(Map.of(
                "health.threshold.psi.cpu.some.critical", "60",
                "health.threshold.psi.io.full.warning", "7",
                "health.threshold.psi.memory.full.critical", "15"));
        assertThat(t.getPsi().getCpu().getSome().getCritical()).isEqualTo(60);
        assertThat(t.getPsi().getCpu().getSome().getWarning()).isEqualTo(30);
        assertThat(t.getPsi().getIo().getFull().getWarning()).isEqualTo(7);
        assertThat(t.getPsi().getMemory().getFull().getCritical()).isEqualTo(15);
    }
}
