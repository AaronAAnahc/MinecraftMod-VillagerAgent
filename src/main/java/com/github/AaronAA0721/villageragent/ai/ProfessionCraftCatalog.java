package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of what each villager profession can craft, by profession and
 * villager level (1 = Novice … 5 = Master).
 *
 * <p>This replaces the old hard-coded {@code RecipeRegistry}. The catalog only lists
 * <em>which items a profession may produce</em> (mirroring the goods a vanilla
 * villager of that profession sells). The actual <em>materials</em> are read at
 * runtime from the game's own {@code RecipeManager} via {@link NativeRecipeResolver},
 * so we never maintain an ingredient list by hand.
 *
 * <p>Items are identified by registry id ({@code minecraft:iron_pickaxe}). Levels
 * follow the vanilla trade tiers; the three gear professions (weaponsmith /
 * toolsmith / armorer) are filled from the real 1.16.5 trades, the rest are
 * seed entries to be verified/extended later (see {@code // VERIFY} markers).
 */
public final class ProfessionCraftCatalog {

    /** One craftable item entry. */
    public static class CraftableEntry {
        public final ResourceLocation item;
        /** Whether a Master-level villager may produce a randomly-enchanted version. */
        public final boolean enchantable;
        /** Whether only a Master (level 5) villager may make it. */
        public final boolean masterOnly;
        public final int level;

        public CraftableEntry(String id, int level, boolean enchantable, boolean masterOnly) {
            this.item = new ResourceLocation(id);
            this.level = level;
            this.enchantable = enchantable;
            this.masterOnly = masterOnly;
        }
    }

    /** profession -> (level -> entries) */
    private static final Map<String, Map<Integer, List<CraftableEntry>>> CATALOG = new HashMap<>();

    static {
        init();
    }

    private ProfessionCraftCatalog() {}

    private static void add(String prof, int level, String id, boolean enchantable, boolean masterOnly) {
        CATALOG.computeIfAbsent(prof, k -> new HashMap<>())
                .computeIfAbsent(level, k -> new ArrayList<>())
                .add(new CraftableEntry(id, level, enchantable, masterOnly));
    }

    private static void init() {
        // ───────────────────────── Weaponsmith ─────────────────────────
        // VERIFY: levels approximate vanilla 1.16.5 trade tiers.
        add("weaponsmith", 1, "minecraft:iron_sword", false, false);
        add("weaponsmith", 1, "minecraft:iron_axe",   false, false);
        add("weaponsmith", 2, "minecraft:iron_sword", true,  false); // enchanted iron sword
        add("weaponsmith", 3, "minecraft:iron_axe",   true,  false); // enchanted iron axe
        add("weaponsmith", 4, "minecraft:diamond_sword", true, false);
        add("weaponsmith", 5, "minecraft:diamond_sword", true, true); // enchanted diamond sword (master)
        add("weaponsmith", 5, "minecraft:bell",        false, true);

        // ───────────────────────── Toolsmith ─────────────────────────
        add("toolsmith", 1, "minecraft:iron_shovel", false, false);
        add("toolsmith", 1, "minecraft:iron_pickaxe", false, false);
        add("toolsmith", 2, "minecraft:iron_axe",    false, false);
        add("toolsmith", 2, "minecraft:iron_hoe",    false, false);
        add("toolsmith", 3, "minecraft:diamond_axe",  true,  false);
        add("toolsmith", 4, "minecraft:diamond_shovel", true, false);
        add("toolsmith", 5, "minecraft:diamond_pickaxe", true, true); // enchanted diamond pickaxe (master)
        add("toolsmith", 5, "minecraft:diamond_hoe",  true,  false);

        // ───────────────────────── Armorer ─────────────────────────
        add("armorer", 1, "minecraft:iron_helmet",     false, false);
        add("armorer", 1, "minecraft:iron_chestplate", false, false);
        add("armorer", 2, "minecraft:iron_leggings",   false, false);
        add("armorer", 2, "minecraft:iron_boots",      false, false);
        add("armorer", 3, "minecraft:diamond_helmet",  true,  false);
        add("armorer", 4, "minecraft:diamond_leggings", true, false);
        add("armorer", 5, "minecraft:diamond_chestplate", true, true); // enchanted diamond chestplate (master)
        add("armorer", 5, "minecraft:chainmail_helmet",  false, true);
        add("armorer", 5, "minecraft:chainmail_chestplate", false, true);
        add("armorer", 5, "minecraft:chainmail_leggings",  false, true);
        add("armorer", 5, "minecraft:chainmail_boots",    false, true);

        // ─────────────── Seed entries for the remaining professions (VERIFY) ───────────────
        add("fletcher",   1, "minecraft:bow",    true,  false);
        add("fletcher",   2, "minecraft:crossbow", true, false);
        add("fletcher",   1, "minecraft:arrow",  false, false);

        add("leatherworker", 1, "minecraft:leather_helmet",     false, false);
        add("leatherworker", 1, "minecraft:leather_chestplate", false, false);
        add("leatherworker", 2, "minecraft:leather_leggings",   false, false);
        add("leatherworker", 2, "minecraft:leather_boots",      false, false);
        add("leatherworker", 5, "minecraft:saddle",             false, true);

        add("mason", 1, "minecraft:stone_bricks",         false, false);
        add("mason", 2, "minecraft:chiseled_stone_bricks", false, false);
        add("mason", 3, "minecraft:polished_andesite",    false, false);

        add("butcher", 1, "minecraft:rabbit_stew", false, false);
        add("butcher", 3, "minecraft:cooked_beef", false, false); // VERIFY: smoked, not crafted

        add("shepherd", 1, "minecraft:white_carpet", false, false);
        add("shepherd", 2, "minecraft:white_wool",   false, false);

        add("cartographer", 1, "minecraft:paper", false, false);
        add("cartographer", 2, "minecraft:map",   false, false);
        add("cartographer", 5, "minecraft:filled_map", false, true);

        add("librarian", 1, "minecraft:book", false, false);
        add("librarian", 5, "minecraft:quill", false, true); // VERIFY

        add("cleric", 1, "minecraft:glass_bottle", false, false);
        add("cleric", 5, "minecraft:ender_pearl", false, true); // VERIFY: not craftable

        add("farmer", 1, "minecraft:bread",        false, false);
        add("farmer", 2, "minecraft:pumpkin_pie",  false, false);
        add("farmer", 3, "minecraft:cake",         false, false);

        add("fisherman", 1, "minecraft:fishing_rod", true, false);
    }

    /**
     * Whether the profession at the given level may craft the item.
     * An entry is craftable when its required level <= villager level, and if it is
     * masterOnly it additionally requires villager level == 5.
     */
    /** Normalize a profession string: lowercase, strip a leading "minecraft:" namespace. */
    private static String norm(String profession) {
        if (profession == null) return "";
        String p = profession.toLowerCase(java.util.Locale.ROOT);
        if (p.startsWith("minecraft:")) p = p.substring("minecraft:".length());
        return p;
    }

    public static boolean isCraftable(String profession, int villagerLevel, String itemId) {
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel == null) return false;
        ResourceLocation rl = new ResourceLocation(itemId);
        for (Map.Entry<Integer, List<CraftableEntry>> e : byLevel.entrySet()) {
            if (e.getKey() > villagerLevel) continue;
            for (CraftableEntry c : e.getValue()) {
                if (c.item.equals(rl)) {
                    if (c.masterOnly && villagerLevel != 5) return false;
                    return true;
                }
            }
        }
        return false;
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

    /** All craftable ids for a profession at or below the given level (masterOnly only at 5). */
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

    /** Every id in the profession's catalog regardless of level (for prompt display). */
    public static List<String> getAllIds(String profession) {
        List<String> out = new ArrayList<>();
        Map<Integer, List<CraftableEntry>> byLevel = CATALOG.get(norm(profession));
        if (byLevel == null) return out;
        for (List<CraftableEntry> list : byLevel.values()) {
            for (CraftableEntry c : list) out.add(c.item.toString());
        }
        return out;
    }
}
