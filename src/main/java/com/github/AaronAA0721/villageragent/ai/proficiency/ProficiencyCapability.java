package com.github.AaronAA0721.villageragent.ai.proficiency;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.nbt.INBT;
import net.minecraft.util.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;

/**
 * Registers the {@link VillagerProficiency} capability and exposes a small static facade
 * so the rest of the mod can add/query proficiency without touching capability plumbing:
 *
 * <pre>
 *   ProficiencyCapability.addXp(villager, 12);
 *   int lvl = ProficiencyCapability.getLevel(villager);
 * </pre>
 */
public final class ProficiencyCapability {

    @CapabilityInject(VillagerProficiency.class)
    public static Capability<VillagerProficiency> CAPABILITY = null;

    private ProficiencyCapability() {}

    /** Call once during mod setup (FMLCommonSetupEvent). */
    public static void register() {
        CapabilityManager.INSTANCE.register(
                VillagerProficiency.class,
                new Capability.IStorage<VillagerProficiency>() {
                    @Nullable
                    @Override
                    public INBT writeNBT(Capability<VillagerProficiency> cap, VillagerProficiency inst, Direction side) {
                        return inst.serializeNBT();
                    }

                    @Override
                    public void readNBT(Capability<VillagerProficiency> cap, VillagerProficiency inst, Direction side, INBT nbt) {
                        inst.deserializeNBT((net.minecraft.nbt.CompoundNBT) nbt);
                    }
                },
                VillagerProficiency::new
        );
    }

    /** The proficiency capability for a villager, or empty if not present. */
    public static LazyOptional<VillagerProficiency> get(VillagerEntity villager) {
        return villager.getCapability(CAPABILITY);
    }

    // ── Static API (the surface the user asked for) ──

    /** Add proficiency XP. Also keeps the vanilla level in sync when configured. */
    public static void addXp(VillagerEntity villager, int amount) {
        get(villager).ifPresent(p -> {
            // Seed up to any level the villager already reached via vanilla trading, then add.
            p.ensureAtLeastVanillaLevel(villager);
            p.addExperience(amount);
            if (ModConfig.PROFICIENCY_SYNC_TO_VANILLA.get()) p.syncToVanilla(villager);
        });
    }

    /** Reduce proficiency XP (never below 0). */
    public static void removeXp(VillagerEntity villager, int amount) {
        get(villager).ifPresent(p -> {
            p.removeExperience(amount);
            if (ModConfig.PROFICIENCY_SYNC_TO_VANILLA.get()) p.syncToVanilla(villager);
        });
    }

    /** Total cumulative proficiency XP. */
    public static int getXp(VillagerEntity villager) {
        return get(villager).map(VillagerProficiency::getExperience).orElse(0);
    }

    /** Current proficiency level (1..maxLevel). */
    public static int getLevel(VillagerEntity villager) {
        return get(villager).map(VillagerProficiency::getLevel).orElse(1);
    }

    /** Mirror proficiency level onto vanilla VillagerData.level. */
    public static void syncToVanilla(VillagerEntity villager) {
        if (ModConfig.PROFICIENCY_SYNC_TO_VANILLA.get()) {
            get(villager).ifPresent(p -> p.syncToVanilla(villager));
        }
    }
}
