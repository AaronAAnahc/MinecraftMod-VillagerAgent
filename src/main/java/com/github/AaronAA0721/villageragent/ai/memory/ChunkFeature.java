package com.github.AaronAA0721.villageragent.ai.memory;

/**
 * A "point of interest" kind that a chunk may contain.
 *
 * <p>Chunk memory stores, per feature, <b>one bit</b> (present / absent) plus <b>one packed
 * representative position</b> (see {@link ChunkMemory#recordFeature}). It deliberately does
 * NOT store every matching block: remembering "there is farmland in this chunk, e.g. at
 * (3, 64, 11)" is enough for recall and navigation, while storing all 256 farmland blocks of
 * a farm chunk was pure waste. Exact, complete detail is re-acquired on arrival by the
 * frustum scan.
 *
 * <p>Ordinals are used for the bitmask and are persisted — <b>append new features at the
 * end</b>, never reorder. Max 32 entries (int bitmask).
 */
public enum ChunkFeature {
    CRAFTING_TABLE("minecraft:crafting_table", "a crafting table"),
    FURNACE("minecraft:furnace", "a furnace"),
    CHEST("minecraft:chest", "a chest"),
    BARREL("minecraft:barrel", "a barrel"),
    BED("minecraft:bed", "a bed"),
    DOOR("minecraft:door", "a door"),
    FARMLAND("minecraft:farmland", "farmland"),
    CROP("minecraft:wheat", "crops"),
    WATER("minecraft:water", "water"),
    LAVA("minecraft:lava", "lava"),
    ORE_COAL("minecraft:coal_ore", "coal ore"),
    ORE_IRON("minecraft:iron_ore", "iron ore"),
    ORE_GOLD("minecraft:gold_ore", "gold ore"),
    ORE_DIAMOND("minecraft:diamond_ore", "diamond ore"),
    ORE_EMERALD("minecraft:emerald_ore", "emerald ore"),
    ORE_REDSTONE("minecraft:redstone_ore", "redstone ore"),
    ORE_LAPIS("minecraft:lapis_ore", "lapis ore"),
    // Appended 2026-11-10 — blocks villagers walk to for work (memory-first block seeking, BlockSeek).
    GRAVEL("minecraft:gravel", "gravel"),
    SMOKER("minecraft:smoker", "a smoker"),
    CAMPFIRE("minecraft:campfire", "a campfire"),
    BLAST_FURNACE("minecraft:blast_furnace", "a blast furnace");

    private final String blockId;
    private final String display;

    ChunkFeature(String blockId, String display) {
        this.blockId = blockId;
        this.display = display;
    }

    public int bit() {
        return 1 << ordinal();
    }

    /** Canonical block id (best effort — BED/DOOR/CROP cover several concrete blocks). */
    public String blockId() {
        return blockId;
    }

    /** Human-readable phrase for LLM prompts. */
    public String display() {
        return display;
    }

    public boolean isOre() {
        return ordinal() >= ORE_COAL.ordinal() && ordinal() <= ORE_LAPIS.ordinal();
    }
}
