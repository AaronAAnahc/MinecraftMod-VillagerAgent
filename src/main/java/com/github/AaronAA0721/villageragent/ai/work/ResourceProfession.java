package com.github.AaronAA0721.villageragent.ai.work;

/** Persistent agent occupations, independent of vanilla POI schedules. */
public enum ResourceProfession {
    NONE, LUMBERJACK, MINER;

    public static ResourceProfession parse(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return NONE; }
    }
}
