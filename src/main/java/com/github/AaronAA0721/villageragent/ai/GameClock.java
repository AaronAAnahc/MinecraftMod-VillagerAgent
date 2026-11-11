package com.github.AaronAA0721.villageragent.ai;

/**
 * In-game clock helpers. Minecraft day time 0 is 06:00, 6000 is noon, 12000 is 18:00, 18000 is
 * midnight; one in-game hour is 1000 ticks. The LLM speaks clock time ("12:30"); goals store ticks.
 */
public final class GameClock {
    public static final long DAY = 24_000L;
    private GameClock() {}

    /** "HH:MM" for a world day time. */
    public static String format(long dayTime) {
        long t = ((dayTime % DAY) + DAY) % DAY;
        long minutesSinceSix = t * 60 / 1000;
        long h = (6 + minutesSinceSix / 60) % 24, m = minutesSinceSix % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", h, m);
    }

    /**
     * Ticks from {@code dayTime} until the next occurrence of clock time {@code hhmm}
     * ("12:00", "7:30", "18"). Returns -1 if it cannot be parsed. A time that is now or just
     * passed (within {@code graceTicks}) is "now" (0), otherwise it means tomorrow.
     */
    public static long ticksUntil(String hhmm, long dayTime, long graceTicks) {
        if (hhmm == null) return -1;
        String s = hhmm.trim();
        int h, m = 0;
        try {
            int colon = s.indexOf(':');
            if (colon < 0) h = Integer.parseInt(s);
            else { h = Integer.parseInt(s.substring(0, colon).trim()); m = Integer.parseInt(s.substring(colon + 1).trim()); }
        } catch (NumberFormatException e) { return -1; }
        if (h < 0 || h > 24 || m < 0 || m > 59) return -1;
        long target = (((h - 6) * 1000L + m * 1000L / 60) % DAY + DAY) % DAY;
        long current = ((dayTime % DAY) + DAY) % DAY;
        long delta = ((target - current) % DAY + DAY) % DAY;
        if (DAY - delta <= graceTicks) return 0; // just passed: start now rather than tomorrow
        return delta;
    }

    /** Human-readable duration for prompts/UI, e.g. "45s" or "3m10s" (real time at 20 tps). */
    public static String duration(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : s + "s";
    }
}
