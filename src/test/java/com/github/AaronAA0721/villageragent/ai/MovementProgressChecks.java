package com.github.AaronAA0721.villageragent.ai;

public final class MovementProgressChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        MovementProgress progress = new MovementProgress();
        check(progress.stationaryTicks(0, 0, 0, 0) == 0, "initial sample");
        for (int tick = 1; tick <= 3000; tick++) {
            if (progress.stationaryTicks(tick, tick * 0.1, 0, 0) > 200)
                throw new AssertionError("long moving route timed out at " + tick);
        }
        check(true, "continuous motion survives 200/400/2400 total ticks");
        progress.stationaryTicks(3010, 301, 0, 0);
        check(progress.stationaryTicks(3210, 301, 0, 0) == 200, "stationary boundary");
        check(progress.stationaryTicks(3211, 301, 0, 0) > 200, "stationary timeout");
        check(progress.stationaryTicks(3212, 302, 0, 0) == 0, "motion resets clock");
        check(progress.stationaryTicks(3213, 302, 1, 0) == 0, "vertical motion counts");
        check(progress.stationaryTicks(3214, 301, 1, 0) == 0, "detours count without approaching target");
        check(progress.stationaryTicks(3415, 301.01, 1, 0) > 200, "tiny jitter cannot hide stalling");
        check(progress.stationaryTicks(1, 301, 1, 0) == 0, "clock rewind resets");
        System.out.println("MovementProgressChecks: " + checks + " assertions passed");
    }
}
