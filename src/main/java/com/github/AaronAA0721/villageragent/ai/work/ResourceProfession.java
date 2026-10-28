package com.github.AaronAA0721.villageragent.ai.work;

/** Cached resource-order role, synchronized from the entity's workstation profession. */
public enum ResourceProfession {
    NONE, LUMBERJACK, MINER;

    public static ResourceProfession parse(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return NONE; }
    }
}
