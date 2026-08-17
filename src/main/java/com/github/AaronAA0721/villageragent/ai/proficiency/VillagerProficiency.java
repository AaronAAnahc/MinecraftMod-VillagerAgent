package com.github.AaronAA0721.villageragent.ai.proficiency;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * Per-villager proficiency: experience + level, distinct from vanilla's trade-only XP
 * but using the same vanilla progression table by default.
 *
 * <p>This is the single source of truth for a villager's proficiency. The actual XP
 * sources (crafting, player trades, villager-villager trades, absorbing mob drops)
 * are wired in later — this class only records and derives state, plus a method to
 * mirror the level back onto vanilla's {@code VillagerData} so the vanilla trading UI
 * and trade offer pools stay in sync.
 *
 * <p>Attached to every {@link VillagerEntity} via a Forge Capability
 * ({@code VillagerProficiencyProvider}); persisted automatically with the entity.
 */
public class VillagerProficiency implements INBTSerializable<CompoundNBT> {

    /** Cumulative experience (matches vanilla semantics: 0 = Novice, 250+ = Master). */
    private int xp = 0;

    private final ProficiencyLevelTable table = ProficiencyLevelTable.instance();

    /** Clamp so the level never exceeds what the (config-driven) progression table allows. */
    private final int maxLevel = table.getMaxLevel();

    // ── Mutators ──
    public void addExperience(int amount) {
        if (amount <= 0) return;
        xp = Math.max(0, xp + amount);
    }

    public void removeExperience(int amount) {
        if (amount <= 0) return;
        xp = Math.max(0, xp - amount);
    }

    public void setExperience(int amount) {
        xp = Math.max(0, amount);
    }

    // ── Accessors ──
    /** Total cumulative proficiency XP. */
    public int getExperience() { return xp; }

    /** XP accumulated within the current level (for progress bars). */
    public int getLevelXp() { return xp - table.xpForLevel(getLevel()); }

    /** Current proficiency level (1..maxLevel). */
    public int getLevel() {
        return Math.min(table.levelForXp(xp), maxLevel);
    }

    public int getMaxLevel() { return maxLevel; }

    public boolean isMaxLevel() { return getLevel() >= maxLevel; }

    /** Remaining XP to reach the next level (0 if at max). */
    public int getExperienceForNextLevel() {
        int lvl = getLevel();
        if (lvl >= maxLevel) return 0;
        return table.xpToNext(lvl) - getLevelXp();
    }

    /** Progress through the current level, 0..1. */
    public float getLevelProgress() { return table.progress(xp, getLevel()); }

    // ── Vanilla bridge ──
    /**
     * Ensure our tracked level is never below a level the villager already reached via
     * vanilla trade-levelling. Called before adding XP so a villager that was already
     * (say) Master through trading starts proficiency at the matching cumulative XP
     * instead of 0.
     */
    public void ensureAtLeastVanillaLevel(VillagerEntity villager) {
        int vanillaLevel = villager.getVillagerData().getLevel();
        if (table.levelForXp(xp) < vanillaLevel) {
            xp = Math.max(xp, table.xpForLevel(vanillaLevel));
        }
    }

    /**
     * Mirror our proficiency level onto the vanilla {@code VillagerData.level} so the
     * trading UI badge and vanilla trade offer pools reflect proficiency progression.
     * Push-up only: we never *lower* the vanilla level (so vanilla trade progress is
     * never lost). Call after any XP change when {@code proficiency_sync_to_vanilla} is enabled.
     */
    public void syncToVanilla(VillagerEntity villager) {
        int ourLevel = getLevel();
        int vanillaLevel = villager.getVillagerData().getLevel();
        if (ourLevel > vanillaLevel) {
            villager.setVillagerData(villager.getVillagerData().setLevel(ourLevel));
        }
    }

    // ── NBT ──
    @Override
    public CompoundNBT serializeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        nbt.putInt("Xp", xp);
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundNBT nbt) {
        xp = nbt.contains("Xp") ? Math.max(0, nbt.getInt("Xp")) : 0;
    }
}
