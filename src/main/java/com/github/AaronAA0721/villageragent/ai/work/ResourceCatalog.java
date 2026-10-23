package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Explicit supported extraction methods. Output is the real 1.16.5 loot, not a minted item. */
public final class ResourceCatalog {
    public static final class Source {
        public final ResourceProfession profession;
        public final Block block;
        public final Item tool;
        Source(ResourceProfession profession, Block block, Item tool) {
            this.profession = profession; this.block = block; this.tool = tool;
        }
    }
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    static {
        log(Items.OAK_LOG, Blocks.OAK_LOG); log(Items.SPRUCE_LOG, Blocks.SPRUCE_LOG);
        log(Items.BIRCH_LOG, Blocks.BIRCH_LOG); log(Items.JUNGLE_LOG, Blocks.JUNGLE_LOG);
        log(Items.ACACIA_LOG, Blocks.ACACIA_LOG); log(Items.DARK_OAK_LOG, Blocks.DARK_OAK_LOG);
        ore(Items.COAL, Blocks.COAL_ORE, Items.WOODEN_PICKAXE);
        ore(Items.IRON_ORE, Blocks.IRON_ORE, Items.STONE_PICKAXE);
        ore(Items.GOLD_ORE, Blocks.GOLD_ORE, Items.IRON_PICKAXE);
        ore(Items.DIAMOND, Blocks.DIAMOND_ORE, Items.IRON_PICKAXE);
        ore(Items.REDSTONE, Blocks.REDSTONE_ORE, Items.IRON_PICKAXE);
        ore(Items.LAPIS_LAZULI, Blocks.LAPIS_ORE, Items.STONE_PICKAXE);
        ore(Items.COBBLESTONE, Blocks.STONE, Items.WOODEN_PICKAXE);
    }
    private static void log(Item item, Block block) {
        SOURCES.put(item.getRegistryName().toString(), new Source(ResourceProfession.LUMBERJACK, block, Items.WOODEN_AXE));
    }
    private static void ore(Item item, Block block, Item tool) {
        SOURCES.put(item.getRegistryName().toString(), new Source(ResourceProfession.MINER, block, tool));
    }
    public static Source get(String item) { return SOURCES.get(item); }
    public static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null ? Items.AIR : item;
    }
    private ResourceCatalog() {}
}
