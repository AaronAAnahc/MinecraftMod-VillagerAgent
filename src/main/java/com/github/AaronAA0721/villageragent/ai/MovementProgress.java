package com.github.AaronAA0721.villageragent.ai;

/** Measures time since physical displacement, including vertical movement and detours. */
public final class MovementProgress {
    private boolean initialized;
    private double x, y, z;
    private long lastMoved;
    public long stationaryTicks(long now, double nextX, double nextY, double nextZ) {
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        if (!initialized || now < lastMoved || dx * dx + dy * dy + dz * dz >= 0.25) {
            initialized = true; x = nextX; y = nextY; z = nextZ; lastMoved = now;
        }
        return Math.max(0, now - lastMoved);
    }
}
