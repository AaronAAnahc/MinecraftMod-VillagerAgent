package com.github.AaronAA0721.villageragent.content;

import com.github.AaronAA0721.villageragent.Villageragent;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityType.Builder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/** Entity types added by the mod. */
public final class ModEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITIES, Villageragent.MOD_ID);

    /** Fishing bobber cast by villager fishermen (see {@link VillagerBobberEntity}). Not saved, not summonable. */
    public static final RegistryObject<EntityType<VillagerBobberEntity>> VILLAGER_BOBBER = ENTITIES.register("villager_bobber",
            () -> Builder.<VillagerBobberEntity>of(VillagerBobberEntity::new, EntityClassification.MISC)
                    .sized(0.25F, 0.25F)
                    .noSave()
                    .noSummon()
                    .clientTrackingRange(4)
                    .updateInterval(1)
                    .build(Villageragent.MOD_ID + ":villager_bobber"));

    public static void register(IEventBus bus) { ENTITIES.register(bus); }

    private ModEntities() {}
}
