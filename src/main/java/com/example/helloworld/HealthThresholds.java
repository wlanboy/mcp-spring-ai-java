package com.example.helloworld;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Warning/critical thresholds for {@link SystemHealthTools#getSystemAnomalies}, bound from
 * {@code health.threshold.*}. Values are percentages unless stated otherwise.
 *
 * <p>JavaBean binding onto pre-initialized nested objects (instead of a record) keeps a
 * per-metric default for every single value, so overriding e.g. only
 * {@code health.threshold.cpu.warning} leaves {@code cpu.critical} at its default.
 */
@ConfigurationProperties("health.threshold")
public class HealthThresholds {

    private final Limits cpu = new Limits(70, 90);

    private final Limits memory = new Limits(80, 90);

    private final Limits swap = new Limits(70, 90);

    private final Limits disk = new Limits(80, 90);

    /** Percentage of each sensor's own critical temperature. */
    private final Limits temperature = new Limits(80, 95);

    /** Linux PSI: percent of time stalled/waiting per 1-second window. */
    private final Psi psi = new Psi();

    public Limits getCpu() { return cpu; }

    public Limits getMemory() { return memory; }

    public Limits getSwap() { return swap; }

    public Limits getDisk() { return disk; }

    public Limits getTemperature() { return temperature; }

    public Psi getPsi() { return psi; }

    public static class Limits {

        private double warning;

        private double critical;

        public Limits() {}

        Limits(double warning, double critical) {
            this.warning = warning;
            this.critical = critical;
        }

        public double getWarning() { return warning; }

        public void setWarning(double warning) { this.warning = warning; }

        public double getCritical() { return critical; }

        public void setCritical(double critical) { this.critical = critical; }
    }

    public static class Psi {

        /** {@code psi.cpu.some}: share of time at least one task waited for CPU. */
        private final Some cpu = new Some(new Limits(30, 70));

        /** {@code psi.io.full}: share of time ALL tasks were stalled on IO. */
        private final Full io = new Full(new Limits(5, 20));

        /** {@code psi.memory.full}: share of time ALL tasks were stalled on memory. */
        private final Full memory = new Full(new Limits(1, 10));

        public Some getCpu() { return cpu; }

        public Full getIo() { return io; }

        public Full getMemory() { return memory; }
    }

    public static class Some {

        private final Limits some;

        Some(Limits some) { this.some = some; }

        public Limits getSome() { return some; }
    }

    public static class Full {

        private final Limits full;

        Full(Limits full) { this.full = full; }

        public Limits getFull() { return full; }
    }
}
