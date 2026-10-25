package com.github.AaronAA0721.villageragent.ai.proficiency;

import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Data-driven mapping from cumulative capability XP → capability level (0..maxLevel).
 *
 * <p>Capability levels are <em>finer</em> than the vanilla trade tiers: default is 11 levels
 * (0 = "untrained" … 11 = Master), and every 2 capability levels map to one vanilla trade level
 * via {@code vanillaLevel = max(0, (capabilityLevel - 1) / 2)}. This is separate from
 * {@link ProficiencyLevelTable} (the 5-tier vanilla trade table that {@link VillagerProficiency}
 * mirrors onto the trading UI).
 *
 * <p>The thresholds are read from the {@code "capability_thresholds"} array of
 * {@code config/villageragent/proficiency.json} (each entry = cumulative XP required to BE at
 * that level, 1-based; level 0 is implicit at 0 XP). A missing/malformed file falls back to the
 * built-in 11-level default below.
 */
public final class CapabilityLevelTable {

    private static final Logger LOGGER = LogManager.getLogger(CapabilityLevelTable.class);

    /**
     * Built-in cumulative XP required to BE at each level (index = level, 0..11).
     * Level 0 = no capability (0 XP). Level 1 = "beginner" (10 XP), level 3 = "first profession tier"
     * (Novice, 50 XP), level 5 = Apprentice, 7 = Journeyman, 9 = Expert, 11 = Master.
     */
    private static final int[] DEFAULT_THRESHOLDS = {
            0,    // 0 — untrained
            10,   // 1 — beginner
            25,   // 2
            50,   // 3 — first profession tier (Novice)
            90,   // 4
            145,  // 5 — second tier (Apprentice)
            215,  // 6
            300,  // 7 — third tier (Journeyman)
            400,  // 8
            515,  // 9 — fourth tier (Expert)
            645,  // 10
            790   // 11 — fifth tier (Master)
    };

    private static CapabilityLevelTable INSTANCE;

    private final int maxLevel;
    /** cumulative[level] = total XP needed to reach `level` (0..maxLevel). cumulative[0] = 0. */
    private final int[] cumulative;

    private CapabilityLevelTable(int maxLevel, int[] cumulative) {
        this.maxLevel = maxLevel;
        this.cumulative = cumulative;
    }

    /** The built-in 11-level default table. */
    public static CapabilityLevelTable defaults() {
        return new CapabilityLevelTable(DEFAULT_THRESHOLDS.length - 1, DEFAULT_THRESHOLDS.clone());
    }

    /** Lazy singleton: config-driven, built-in fallback. Use this everywhere. */
    public static CapabilityLevelTable instance() {
        if (INSTANCE == null) INSTANCE = loadFromConfig();
        return INSTANCE;
    }

    private static CapabilityLevelTable loadFromConfig() {
        try (InputStream in = VillagerAgentConfigDir.open("proficiency.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray arr = root.getAsJsonArray("capability_thresholds");
            if (arr == null || arr.size() == 0) {
                LOGGER.info("[VillagerAgent] No capability_thresholds in proficiency.json — using built-in 11-level table");
                return defaults();
            }
            int m = arr.size(); // levels 1..m
            int[] cum = new int[m + 1];
            cum[0] = 0;
            for (int i = 0; i < m; i++) cum[i + 1] = Math.max(0, arr.get(i).getAsInt());
            LOGGER.info("[VillagerAgent] Loaded capability level table: {} levels from capability_thresholds", m);
            return new CapabilityLevelTable(m, cum);
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] capability_thresholds load failed (using built-in table): {}", ex.getMessage());
            return defaults();
        }
    }

    public int getMaxLevel() { return maxLevel; }

    /** Total cumulative XP required to reach `level` (clamped to [0, maxLevel]). */
    public int xpForLevel(int level) {
        if (level <= 0) return 0;
        if (level > maxLevel) return cumulative[maxLevel];
        return cumulative[level];
    }

    /** Derive the level (0..maxLevel) for a given cumulative XP. 0 = "untrained". */
    public int levelForXp(int xp) {
        if (xp < cumulative[1]) return 0; // below "beginner" — no capability
        int lvl = 1;
        for (int l = 2; l <= maxLevel; l++) {
            if (xp >= cumulative[l]) lvl = l;
            else break;
        }
        return lvl;
    }

    @Override
    public String toString() {
        return "CapabilityLevelTable{maxLevel=" + maxLevel + ", cumulative=" + Arrays.toString(cumulative) + "}";
    }
}
