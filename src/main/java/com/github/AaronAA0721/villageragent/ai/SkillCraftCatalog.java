package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.config.VillagerAgentConfigDir;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityPool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unified catalog of what a villager can craft, keyed by profession AND capability.
 *
 * <p>Renamed from {@code ProfessionCraftCatalog} to reflect that a recipe is no longer a purely
 * professional monopoly: each recipe declares a {@code requiredCapability} + {@code requiredLevel}
 * (capability levels, 0..11). A librarian who grinds {@code CRAFTING} can make universal goods but
 * still cannot make a fishing rod (that needs {@code FISHING}) — the professional moat is preserved
 * while {@code CRAFTING} keeps independent value.
 *
 * <p>Material resolution is unchanged: a villager first tries the vanilla recipe via
 * {@link NativeRecipeResolver#resolveRecipe}; if none exists it falls back to a custom recipe from
 * {@code custom_recipes.json} ({@link CustomRecipeRegistry}).
 */
public final class SkillCraftCatalog {

    private static final Logger LOGGER = LogManager.getLogger(SkillCraftCatalog.class);

    /** One craftable item entry. */
    public static class CraftableEntry {
        public final ResourceLocation item;
        /** Whether a Master-level villager may produce a randomly-enchanted version. */
        public final boolean enchantable;
        /** Whether only a Master (level 5) villager may make it. */
        public final boolean masterOnly;
        /** Whether this item has no vanilla recipe and must be crafted via a custom JSON recipe. */
        public final boolean usesCustomRecipe;
        /** Vanilla trade tier (1..5) — kept for the vanilla trade-UI path. */
        public final int level;
        /** The capability whose level gates this recipe (universal goods = {@code CRAFTING}). */
        public final CapabilityId requiredCapability;
        /** Capability level required to craft it (= {@code level * 2 + 1} for profession goods). */
        public final int requiredLevel;

        public CraftableEntry(String id, int level, boolean enchantable, boolean masterOnly, boolean usesCustomRecipe,
                              CapabilityId requiredCapability, int requiredLevel) {
            this.item = new ResourceLocation(id);
            this.level = level;
            this.enchantable = enchantable;
            this.masterOnly = masterOnly;
            this.usesCustomRecipe = usesCustomRecipe;
            this.requiredCapability = requiredCapability;
            this.requiredLevel = requiredLevel;
        }
    }

    /** profession -> (level -> entries) */
    private static final Map<String, Map<Integer, List<CraftableEntry>>> CATALOG = new HashMap<>();
    /** universal (crafting-capability) entries -> (requiredLevel -> entries) */
    private static final Map<Integer, List<CraftableEntry>> UNIVERSAL = new HashMap<>();
    /**
     * capability -> (level -> item ids) loaded from {@code craft_unlocks.json} ("skills" block).
     * This is the authoritative per-skill unlock table: a villager may craft the union of every
     * (capability, level) its pool has reached — independent of any generic CRAFTING gate.
     */
    private static final Map<CapabilityId, Map<Integer, List<String>>> SKILL_UNLOCKS = new HashMap<>();

    static {
        init();
    }

    private SkillCraftCatalog() {}

    private static void add(String prof, int level, String id, boolean enchantable, boolean masterOnly) {
        add(prof, level, id, enchantable, masterOnly, false);
    }

    private static void add(String prof, int level, String id, boolean enchantable, boolean masterOnly, boolean usesCustomRecipe) {
        CapabilityId cap = CapabilityId.mainForProfession(prof);
        CATALOG.computeIfAbsent(prof, k -> new HashMap<>())
                .computeIfAbsent(level, k -> new ArrayList<>())
                .add(new CraftableEntry(id, level, enchantable, masterOnly, usesCustomRecipe, cap, level * 2 + 1));
    }

    /** A universal good any villager can make with enough CRAFTING. */
    private static void addUniversal(int requiredLevel, String id) {
        UNIVERSAL.computeIfAbsent(requiredLevel, k -> new ArrayList<>())
                .add(new CraftableEntry(id, 0, false, false, false, CapabilityId.CRAFTING, requiredLevel));
    }

    private static void init() {
        // ───────────────────────── Weaponsmith ─────────────────────────
        add("weaponsmith", 1, "minecraft:iron_sword", false, false);
        add("weaponsmith", 1, "minecraft:iron_axe",   false, false);
        add("weaponsmith", 2, "minecraft:iron_sword", true,  false);
        add("weaponsmith", 3, "minecraft:iron_axe",   true,  false);
        add("weaponsmith", 4, "minecraft:diamond_sword", true, false);
        add("weaponsmith", 5, "minecraft:diamond_sword", true, true);
        add("weaponsmith", 5, "minecraft:bell",        false, true);

        // ───────────────────────── Toolsmith ─────────────────────────
        add("toolsmith", 1, "minecraft:iron_shovel", false, false);
        add("toolsmith", 1, "minecraft:iron_pickaxe", false, false);
        add("toolsmith", 2, "minecraft:iron_axe",    false, false);
        add("toolsmith", 2, "minecraft:iron_hoe",    false, false);
        add("toolsmith", 3, "minecraft:diamond_axe",  true,  false);
        add("toolsmith", 4, "minecraft:diamond_shovel", true, false);
        add("toolsmith", 5, "minecraft:diamond_pickaxe", true, true);
        add("toolsmith", 5, "minecraft:diamond_hoe",  true,  false);

        // ───────────────────────── Armorer ─────────────────────────
        add("armorer", 1, "minecraft:iron_helmet",     false, false);
        add("armorer", 1, "minecraft:iron_chestplate", false, false);
        add("armorer", 2, "minecraft:iron_leggings",   false, false);
        add("armorer", 2, "minecraft:iron_boots",      false, false);
        add("armorer", 3, "minecraft:diamond_helmet",  true,  false);
        add("armorer", 4, "minecraft:diamond_leggings", true, false);
        add("armorer", 5, "minecraft:diamond_chestplate", true, true);
        add("armorer", 5, "minecraft:chainmail_helmet",     false, true, true);
        add("armorer", 5, "minecraft:chainmail_chestplate", false, true, true);
        add("armorer", 5, "minecraft:chainmail_leggings",   false, true, true);
        add("armorer", 5, "minecraft:chainmail_boots",      false, true, true);

        // ─────────────── Seed entries for the remaining professions ───────────────
        add("fletcher",   1, "minecraft:bow",    true,  false);
        add("fletcher",   2, "minecraft:crossbow", true, false);
        add("fletcher",   1, "minecraft:arrow",  false, false);

        add("leatherworker", 1, "minecraft:leather_helmet",     false, false);
        add("leatherworker", 1, "minecraft:leather_chestplate", false, false);
        add("leatherworker", 2, "minecraft:leather_leggings",   false, false);
        add("leatherworker", 2, "minecraft:leather_boots",      false, false);
        add("leatherworker", 5, "minecraft:saddle",             false, true, true);

        add("mason", 1, "minecraft:stone_bricks",         false, false);
        add("mason", 2, "minecraft:chiseled_stone_bricks", false, false);
        add("mason", 3, "minecraft:polished_andesite",    false, false);

        add("butcher", 1, "minecraft:rabbit_stew", false, false);
        add("butcher", 3, "minecraft:cooked_beef", false, false);

        add("shepherd", 1, "minecraft:white_carpet", false, false);
        add("shepherd", 2, "minecraft:white_wool",   false, false);

        add("cartographer", 1, "minecraft:paper", false, false);
        add("cartographer", 2, "minecraft:map",   false, false);
        add("cartographer", 5, "minecraft:filled_map", false, true, true);

        add("librarian", 1, "minecraft:book", false, false);
        add("librarian", 5, "minecraft:quill", false, true, true);

        add("cleric", 1, "minecraft:glass_bottle", false, false);
        add("cleric", 5, "minecraft:ender_pearl", false, true, true);

        add("farmer", 1, "minecraft:bread",        false, false);
        add("farmer", 2, "minecraft:pumpkin_pie",  false, false);
        add("farmer", 3, "minecraft:cake",         false, false);

        add("fisherman", 1, "minecraft:fishing_rod", true, false);

        // ─────────────── Universal "crafting" goods (any profession) ───────────────
        addUniversal(1, "minecraft:wooden_sword");
        addUniversal(1, "minecraft:wooden_pickaxe");
        addUniversal(1, "minecraft:wooden_axe");
        addUniversal(1, "minecraft:wooden_shovel");
        addUniversal(1, "minecraft:wooden_hoe");
        addUniversal(1, "minecraft:stick");
        addUniversal(1, "minecraft:oak_planks");
        addUniversal(1, "minecraft:crafting_table");
        addUniversal(2, "minecraft:chest");
        addUniversal(2, "minecraft:furnace");
        addUniversal(2, "minecraft:torch");
        addUniversal(2, "minecraft:bread");

        // Apply per-(profession, level) unlock overrides from the "unlocks" block of
        // config/villageragent/proficiency.json (additive over the built-in catalog).
        applyUnlockOverrides();

        // Load the per-skill unlock table from config/villageragent/craft_unlocks.json.
        // This is the primary "what can I craft" source (farmer seeds/hoe + trade-tier recipes);
        // the built-in catalog above stays as a fallback for professions not yet in the JSON.
        loadSkillUnlocks();
    }

    /**
     * Additively merge overrides from the {@code "unlocks"} block of
     * {@code config/villageragent/proficiency.json}:
     * schema {@code { "profession": { "level": ["item_id", ...] } }}.
     */
    private static void applyUnlockOverrides() {
        try (InputStream in = VillagerAgentConfigDir.open("proficiency.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (!root.has("unlocks") || !root.get("unlocks").isJsonObject()) return;
            JsonObject unlocks = root.getAsJsonObject("unlocks");
            int added = 0;
            for (Map.Entry<String, JsonElement> profEntry : unlocks.entrySet()) {
                String prof = norm(profEntry.getKey());
                if (!profEntry.getValue().isJsonObject()) continue;
                JsonObject byLevel = profEntry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> lvlEntry : byLevel.entrySet()) {
                    int lvl;
                    try { lvl = Integer.parseInt(lvlEntry.getKey()); } catch (NumberFormatException e) { continue; }
                    if (!lvlEntry.getValue().isJsonArray()) continue;
                    for (JsonElement itemEl : lvlEntry.getValue().getAsJsonArray()) {
                        String id = itemEl.getAsString();
                        ResourceLocation rl = new ResourceLocation(id);
                        boolean exists = CATALOG.getOrDefault(prof, Collections.emptyMap())
                                .getOrDefault(lvl, Collections.emptyList()).stream()
                                .anyMatch(c -> c.item.equals(rl));
                        if (exists) continue;
                        CapabilityId cap = CapabilityId.mainForProfession(prof);
                        CATALOG.computeIfAbsent(prof, k -> new HashMap<>())
                                .computeIfAbsent(lvl, k -> new ArrayList<>())
                                .add(new CraftableEntry(id, lvl, false, lvl == 5, true, cap, lvl * 2 + 1));
                        added++;
                    }
                }
            }
            if (added > 0) LOGGER.info("[VillagerAgent] Applied {} custom unlock override(s) from proficiency.json", added);
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] proficiency.json unlock load failed (using built-in catalog only): {}", ex.getMessage());
        }
    }

    /** Normalize a profession string: lowercase, strip a leading "minecraft:" namespace. */
    private static String norm(String profession) {
        if (profession == null) return "";
        String p = profession.toLowerCase(Locale.ROOT);
        if (p.startsWith("minecraft:")) p = p.substring("minecraft:".length());
        return p;
    }

    /**
     * Load the per-skill unlock table from {@code config/villageragent/craft_unlocks.json}.
     * Schema: {@code { "skills": { "<capability_id>": { "<level>": ["item_id", ...] } } }}.
     * Levels are capability levels (1..5 by convention; higher is fine). A villager whose pool
     * has {@code levelOf(cap) >= level} may craft every item listed at that level or below.
     */
    private static void loadSkillUnlocks() {
        try (InputStream in = VillagerAgentConfigDir.open("craft_unlocks.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (!root.has("skills") || !root.get("skills").isJsonObject()) return;
            JsonObject skills = root.getAsJsonObject("skills");
            int added = 0;
            for (Map.Entry<String, JsonElement> skillEntry : skills.entrySet()) {
                CapabilityId cap = CapabilityId.fromId(skillEntry.getKey());
                if (cap == null || !skillEntry.getValue().isJsonObject()) continue;
                JsonObject byLevel = skillEntry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> lvlEntry : byLevel.entrySet()) {
                    int lvl;
                    try { lvl = Integer.parseInt(lvlEntry.getKey()); } catch (NumberFormatException e) { continue; }
                    if (!lvlEntry.getValue().isJsonArray()) continue;
                    List<String> ids = new ArrayList<>();
                    for (JsonElement itemEl : lvlEntry.getValue().getAsJsonArray()) {
                        String id = itemEl.getAsString();
                        if (id.contains(" ")) continue; // guard against stray commas / comments
                        ids.add(id);
                    }
                    if (ids.isEmpty()) continue;
                    SKILL_UNLOCKS.computeIfAbsent(cap, k -> new HashMap<>()).put(lvl, ids);
                    added += ids.size();
                }
            }
            if (added > 0) LOGGER.info("[VillagerAgent] Loaded {} craft unlock(s) from craft_unlocks.json", added);
        } catch (Exception ex) {
            LOGGER.warn("[VillagerAgent] craft_unlocks.json load failed (using built-in catalog only): {}", ex.getMessage());
        }
    }

    /** Whether the JSON skill-unlock table grants {@code itemId} at the pool's current levels. */
    private static boolean jsonUnlocksItem(CapabilityPool pool, String itemId) {
        String rl = itemId;
        for (Map.Entry<CapabilityId, Map<Integer, List<String>>> e : SKILL_UNLOCKS.entrySet()) {
            int lvl = pool.levelOf(e.getKey());
            for (Map.Entry<Integer, List<String>> le : e.getValue().entrySet()) {
                if (le.getKey() <= lvl && le.getValue().contains(rl)) return true;
            }
        }
        return false;
    }

    /** The JSON unlock level for {@code itemId}, or null if it is not in the skill table. */
    private static Integer jsonLevelOf(String itemId) {
        for (Map.Entry<CapabilityId, Map<Integer, List<String>>> e : SKILL_UNLOCKS.entrySet()) {
            for (Map.Entry<Integer, List<String>> le : e.getValue().entrySet()) {
                if (le.getValue().contains(itemId)) return le.getKey();
            }
        }
        return null;
    }

    /** Find a profession entry for an item at or below a vanilla level, or null. */
    private static CraftableEntry findProfessionEntry(String profession, int villagerLevel, String itemId) {
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel == null) return null;
        ResourceLocation rl = new ResourceLocation(itemId);
        for (Map.Entry<Integer, List<CraftableEntry>> e : byLevel.entrySet()) {
            if (e.getKey() > villagerLevel) continue;
            for (CraftableEntry c : e.getValue()) {
                if (c.item.equals(rl)) {
                    if (c.masterOnly && villagerLevel != 5) return null;
                    return c;
                }
            }
        }
        return null;
    }

    /** Whether the profession at the given vanilla level may craft the item (legacy trade path). */
    public static boolean isCraftable(String profession, int villagerLevel, String itemId) {
        return findProfessionEntry(profession, villagerLevel, itemId) != null;
    }

    /** Whether a Master-level villager may produce an enchanted version of the item. */
    public static boolean canEnchant(String profession, int villagerLevel, String itemId) {
        if (villagerLevel != 5) return false;
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel == null) return false;
        ResourceLocation rl = new ResourceLocation(itemId);
        for (List<CraftableEntry> list : byLevel.values()) {
            for (CraftableEntry c : list) {
                if (c.item.equals(rl) && c.enchantable) return true;
            }
        }
        return false;
    }

    /** All craftable ids for a profession at or below the given vanilla level (masterOnly only at 5). */
    public static List<String> getCraftableIds(String profession, int villagerLevel) {
        List<String> out = new ArrayList<>();
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel == null) return out;
        for (Map.Entry<Integer, List<CraftableEntry>> e : byLevel.entrySet()) {
            if (e.getKey() > villagerLevel) continue;
            for (CraftableEntry c : e.getValue()) {
                if (c.masterOnly && villagerLevel != 5) continue;
                out.add(c.item.toString());
            }
        }
        return out;
    }

    // ── Capability-gated access (the new model) ────────────────────────────────

    /** Whether the villager's capability pool unlocks this item (JSON skill table OR built-in). */
    public static boolean isUnlockedFor(CapabilityPool pool, String profession, String itemId) {
        // Per-skill unlock table (craft_unlocks.json) — checked first, it is the authoritative gate.
        if (jsonUnlocksItem(pool, itemId)) return true;

        ResourceLocation rl = new ResourceLocation(itemId);
        // Profession entry
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel != null) {
            for (List<CraftableEntry> list : byLevel.values()) {
                for (CraftableEntry c : list) {
                    if (c.item.equals(rl) && c.requiredCapability != null
                            && pool.levelOf(c.requiredCapability) >= c.requiredLevel) {
                        return true;
                    }
                }
            }
        }
        // Universal entry
        for (List<CraftableEntry> list : UNIVERSAL.values()) {
            for (CraftableEntry c : list) {
                if (c.item.equals(rl) && c.requiredCapability != null
                        && pool.levelOf(c.requiredCapability) >= c.requiredLevel) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The capability level required to craft this item, or 0 if not craftable by this villager. */
    public static int requiredLevelFor(String profession, String itemId) {
        // JSON skill table first (its levels are plain capability levels).
        Integer jsonLvl = jsonLevelOf(itemId);
        if (jsonLvl != null) return jsonLvl;

        ResourceLocation rl = new ResourceLocation(itemId);
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel != null) {
            for (List<CraftableEntry> list : byLevel.values()) {
                for (CraftableEntry c : list) {
                    if (c.item.equals(rl)) return c.requiredLevel;
                }
            }
        }
        for (List<CraftableEntry> list : UNIVERSAL.values()) {
            for (CraftableEntry c : list) {
                if (c.item.equals(rl)) return c.requiredLevel;
            }
        }
        return 0;
    }

    /** Every item id (profession + universal + per-skill JSON) this pool currently unlocks. */
    public static List<String> getUnlockedIds(CapabilityPool pool, String profession) {
        Set<String> out = new LinkedHashSet<>();
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel != null) {
            for (List<CraftableEntry> list : byLevel.values()) {
                for (CraftableEntry c : list) {
                    if (c.requiredCapability != null && pool.levelOf(c.requiredCapability) >= c.requiredLevel) {
                        out.add(c.item.toString());
                    }
                }
            }
        }
        for (List<CraftableEntry> list : UNIVERSAL.values()) {
            for (CraftableEntry c : list) {
                if (c.requiredCapability != null && pool.levelOf(c.requiredCapability) >= c.requiredLevel) {
                    out.add(c.item.toString());
                }
            }
        }
        // Per-skill unlock table — every (capability, level) the pool has reached.
        for (Map.Entry<CapabilityId, Map<Integer, List<String>>> e : SKILL_UNLOCKS.entrySet()) {
            int lvl = pool.levelOf(e.getKey());
            for (Map.Entry<Integer, List<String>> le : e.getValue().entrySet()) {
                if (le.getKey() <= lvl) out.addAll(le.getValue());
            }
        }
        return new ArrayList<>(out);
    }
}
