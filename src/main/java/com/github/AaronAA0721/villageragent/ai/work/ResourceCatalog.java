package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropsBlock;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.item.ItemStack;
import net.minecraft.item.AxeItem;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
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
        public final CapabilityId capability;
        public final Item output;
        Source(ResourceProfession profession, Block block, Item tool, CapabilityId capability, Item output) {
            this.profession = profession; this.block = block; this.tool = tool;
            this.capability = capability; this.output = output;
        }
        public boolean matches(BlockState state) {
            return state.getBlock() == block && (!(block instanceof CropsBlock) || ((CropsBlock) block).isMaxAge(state));
        }
        public boolean suitable(ItemStack stack) {
            if (tool == Items.AIR) return true;
            if (stack.isEmpty()) return false;
            if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0 && block.asItem() != output) return false;
            return capability == CapabilityId.WOODCUTTING ? stack.getItem() instanceof AxeItem
                    : stack.isCorrectToolForDrops(block.defaultBlockState());
        }
        public ItemStack availableTool(VillagerAgentData agent) {
            if (tool == Items.AIR) return ItemStack.EMPTY;
            for (ItemStack stack : agent.getInventory().getItems()) if (suitable(stack)) return stack;
            return ItemStack.EMPTY;
        }
        public boolean hasTool(VillagerAgentData agent) { return tool == Items.AIR || !availableTool(agent).isEmpty(); }
        public boolean skilled(VillagerAgentData agent) { return agent.getCapabilityPool().levelOf(capability) >= 1; }
        public String methodId() { return capability.name().toLowerCase(Locale.ROOT); }
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
        crop(Items.WHEAT, Blocks.WHEAT); crop(Items.CARROT, Blocks.CARROTS);
        crop(Items.POTATO, Blocks.POTATOES); crop(Items.BEETROOT, Blocks.BEETROOTS);
    }
    private static void log(Item item, Block block) {
        SOURCES.put(item.getRegistryName().toString(), new Source(ResourceProfession.LUMBERJACK, block, Items.WOODEN_AXE, CapabilityId.WOODCUTTING, item));
    }
    private static void ore(Item item, Block block, Item tool) {
        SOURCES.put(item.getRegistryName().toString(), new Source(ResourceProfession.MINER, block, tool, item == Items.COBBLESTONE ? CapabilityId.QUARRYING : CapabilityId.MINING, item));
    }
    private static void crop(Item item, Block block) {
        SOURCES.put(item.getRegistryName().toString(), new Source(ResourceProfession.NONE, block, Items.AIR, CapabilityId.FARMING, item));
    }
    public static Collection<Source> all() { return Collections.unmodifiableCollection(SOURCES.values()); }
    public static Source get(String item) { return SOURCES.get(item); }
    public static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null ? Items.AIR : item;
    }
    private ResourceCatalog() {}
}
