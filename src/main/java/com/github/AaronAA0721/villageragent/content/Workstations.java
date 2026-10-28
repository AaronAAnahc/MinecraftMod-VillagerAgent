package com.github.AaronAA0721.villageragent.content;

import com.github.AaronAA0721.villageragent.Villageragent;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.AbstractBlock;
import net.minecraft.entity.merchant.villager.VillagerProfession;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.util.SoundEvents;
import net.minecraft.village.PointOfInterestType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.*;

/** Plain blocks for now: POI ownership works independently of player interaction/UI. */
public final class Workstations {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Villageragent.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Villageragent.MOD_ID);
    private static final DeferredRegister<PointOfInterestType> POIS = DeferredRegister.create(ForgeRegistries.POI_TYPES, Villageragent.MOD_ID);
    private static final DeferredRegister<VillagerProfession> JOBS = DeferredRegister.create(ForgeRegistries.PROFESSIONS, Villageragent.MOD_ID);

    public static final RegistryObject<Block> CARPENTER_TABLE = BLOCKS.register("carpenter_table",
            () -> new Block(AbstractBlock.Properties.copy(Blocks.OAK_LOG)));
    public static final RegistryObject<Block> MINER_WORKBENCH = BLOCKS.register("miner_workbench",
            () -> new Block(AbstractBlock.Properties.copy(Blocks.STONE)));
    public static final RegistryObject<PointOfInterestType> LUMBERJACK_POI = POIS.register("lumberjack",
            () -> new PointOfInterestType("lumberjack", PointOfInterestType.getBlockStates(CARPENTER_TABLE.get()), 1, 1));
    public static final RegistryObject<PointOfInterestType> MINER_POI = POIS.register("miner",
            () -> new PointOfInterestType("miner", PointOfInterestType.getBlockStates(MINER_WORKBENCH.get()), 1, 1));
    public static final RegistryObject<VillagerProfession> LUMBERJACK = JOBS.register("lumberjack",
            () -> new VillagerProfession("lumberjack", LUMBERJACK_POI.get(), ImmutableSet.of(), ImmutableSet.of(), SoundEvents.VILLAGER_WORK_FLETCHER));
    public static final RegistryObject<VillagerProfession> MINER = JOBS.register("miner",
            () -> new VillagerProfession("miner", MINER_POI.get(), ImmutableSet.of(), ImmutableSet.of(), SoundEvents.VILLAGER_WORK_MASON));
    static {
        ITEMS.register("carpenter_table", () -> new BlockItem(CARPENTER_TABLE.get(), new Item.Properties().tab(ItemGroup.TAB_DECORATIONS)));
        ITEMS.register("miner_workbench", () -> new BlockItem(MINER_WORKBENCH.get(), new Item.Properties().tab(ItemGroup.TAB_DECORATIONS)));
    }
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); POIS.register(bus); JOBS.register(bus);
        // Forge's POI registry callback installs all block states; no reflective vanilla mutation.
    }
    private Workstations() {}
}
