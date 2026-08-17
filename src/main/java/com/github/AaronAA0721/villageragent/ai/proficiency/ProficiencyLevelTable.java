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
 * Data-driven mapping from cumulative experience → proficiency level.
 *
 * <p>Default is a straight copy of vanilla Minecraft 1.16.5 villager progression:
 * the level is derived from the cumulative XP a villager has, using the same
 * thresholds vanilla stores in {@code VillagerData.NEXT_LEVEL_XP_THRESHOLDS}
 * (Novice 0 / Apprentice 10 / Journeyman 70 / Expert 150 / Master 250).
 *
 * <p>The table is loaded from {@code config/villageragent/proficiency.json} on first use
 * (extracted to the config folder on first run). Edit that file to change the number of
 * levels or the XP required per level; a missing/malformed file falls back to the vanilla
 * table above. {@link #instance()} is the single entry point callers should use.
 */
public final class ProficiencyLevelTable {

    private static final Logger LOGGER = LogManager.getLogger(ProficiencyLevelTable.class);

    /**
     * Cumulative XP required to BE at each level, indexed by level (1-based; index 0 unused).
     * This is the vanilla 1.16.5 table.
     */
    private static final int[] VANILLA_THRESHOLDS = { 0, 0, 10, 70, 150, 250 };

    private static ProficiencyLevelTable INSTANCE;

    private final int maxLevel;
    /** cumulative[level] = total XP needed to reach `level` (1..maxLevel). cumulative[1] = 0. */
    private final int[] cumulative;

    private ProficiencyLevelTable(int maxLevel, int[] cumulative) {
        this.maxLevel = maxLevel;
        this.cumulative = cumulative;
    }

    /** Vanilla 1.16.5 progression: 5 levels, thresholds 0/10/70/150/250. */
    public static ProficiencyLevelTable vanilla() {
        return new ProficiencyLevelTable(5, VANILLA_THRESHOLDS.clone());
    }

    /** Lazy-loaded singleton: config-driven table, vanilla fallback. Use this everywhere. */
    public static ProficiencyLevelTable instance() {
        if (INSTANCE == null) INSTANCE = loadFromConfig();
        return INSTANCE;
    }

    private static ProficiencyLevelTable loadFromConfig() {
        try (InputStream in = VillagerAgentConfigDir.open("proficiency.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray thresholds = root.getAsJsonArray("thresholds");
            int m = thresholds.size(); // number of levels (thresholds[0] = level 1 = 0 XP)
            int[] cum = new int[m + 1];
            cum[1] = 0;
            for (int i = 0; i < m; i++) cum[i + 1] = Math.max(0, thresholds.get(i).getAsInt());
            if (root.has("max_level") && root.get("max_level").getAsInt() != m) {
                LOGGER.warn("[VillagerAgent] proficiency.json max_level ({}) disagrees with thresholds length ({}); "
                        + "using thresholds length as the level count", root.get("max_level").getAsInt(), m);
            }
            LOGGER.info("[VillagerAgent] Loaded proficiency table from config/villageragent/proficiency.json: {} levels", m);
            return new ProficiencyLevelTable(m, cum);
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] proficiency.json load failed (using vanilla table): {}", ex.getMessage());
            return vanilla();
        }
    }

    /**
     * Build a custom table.
     *
     * @param maxLevel        highest reachable level (>=1)
     * @param xpForNextLevel  xpForNextLevel[i] = XP needed to go from level i+1 to i+2
     *                        (i.e. index 0 = Novice→Apprentice, index 1 = Apprentice→Journeyman, ...).
     *                        Missing entries default to 0 (the level would unlock with no extra XP).
     */
    public static ProficiencyLevelTable of(int maxLevel, int... xpForNextLevel) {
        int m = Math.max(1, maxLevel);
        int[] cum = new int[m + 1];
        cum[1] = 0;
        for (int l = 2; l <= m; l++) {
            int need = (l - 2 < xpForNextLevel.length) ? Math.max(0, xpForNextLevel[l - 2]) : 0;
            cum[l] = cum[l - 1] + need;
        }
        return new ProficiencyLevelTable(m, cum);
    }

    public int getMaxLevel() { return maxLevel; }

    /** Total cumulative XP required to reach `level` (clamped to [1, maxLevel]). */
    public int xpForLevel(int level) {
        if (level <= 1) return 0;
        if (level > maxLevel) return cumulative[maxLevel];
        return cumulative[level];
    }

    /** Derive the level for a given cumulative XP (clamped to [1, maxLevel]). */
    public int levelForXp(int xp) {
        int lvl = 1;
        for (int l = 2; l <= maxLevel; l++) {
            if (xp >= cumulative[l]) lvl = l;
            else break;
        }
        return lvl;
    }

    /** XP span needed to advance from `level` to `level+1`; 0 if already at max. */
    public int xpToNext(int level) {
        if (level >= maxLevel) return 0;
        return cumulative[level + 1] - cumulative[level];
    }

    /** Progress (0..1) within the current level. */
    public float progress(int xp, int level) {
        if (level >= maxLevel) return 1f;
        int base = cumulative[level];
        int span = cumulative[level + 1] - base;
        if (span <= 0) return 1f;
        return Math.max(0f, Math.min(1f, (float) (xp - base) / span));
    }

    @Override
    public String toString() {
        return "ProficiencyLevelTable{maxLevel=" + maxLevel + ", cumulative=" + Arrays.toString(cumulative) + "}";
    }
}
