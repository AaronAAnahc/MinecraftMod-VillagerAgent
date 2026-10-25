package com.github.AaronAA0721.villageragent.ai.proficiency;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraftforge.common.util.INBTSerializable;

import com.github.AaronAA0721.villageragent.config.ModConfig;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-villager capability table: what the villager knows, and how strong.
 *
 * <p>One instance per villager, stored on {@code VillagerAgentData} (not a Forge capability —
 * only AI villagers need it, and it must sit close to the planner / skill gate). Each
 * {@link CapabilityId} maps to an integer XP, and its level is derived through
 * {@link CapabilityLevelTable} (0 = "untrained", 11 = Master).
 *
 * <p>XP grows by <em>doing</em> (practice) and by <em>talking to an expert</em> (chat), and
 * <em>decays</em> if a capability sits unused for a while (see {@link #decayTo}). Levels gate
 * skills ({@code VillagerSkill.canHandle}) and recipes ({@code SkillCraftCatalog}).
 */
public class CapabilityPool implements INBTSerializable<CompoundNBT> {

    /** Days after the 2-day grace period before XP fully rusts away (square curve). */
    public static final int RUST_DAYS = 14;
    /** Free days after last practice during which no decay happens. */
    public static final int RUST_GRACE_DAYS = 2;

    private final Map<CapabilityId, Integer> xpByCapability = new EnumMap<>(CapabilityId.class);
    private final Map<CapabilityId, Long> lastPracticedDay = new EnumMap<>(CapabilityId.class);

    private final CapabilityLevelTable table = CapabilityLevelTable.instance();

    /** Capability level (0 = untrained … {@code table.maxLevel}). */
    public int levelOf(CapabilityId id) {
        Integer xp = xpByCapability.get(id);
        return xp == null ? 0 : table.levelForXp(xp);
    }

    /** Raw XP in this capability (0 if never trained). */
    public int xpOf(CapabilityId id) {
        Integer xp = xpByCapability.get(id);
        return xp == null ? 0 : xp;
    }

    /** The Minecraft day (gameTime / 24000) this capability was last practised, or -1. */
    public long lastPracticed(CapabilityId id) {
        Long d = lastPracticedDay.get(id);
        return d == null ? -1L : d;
    }

    /** Set a capability to an exact level (used for birth hobby / profession claim). */
    public void setLevel(CapabilityId id, int level) {
        int lvl = Math.max(0, Math.min(table.getMaxLevel(), level));
        if (lvl == 0) {
            xpByCapability.remove(id);
            lastPracticedDay.remove(id);
        } else {
            xpByCapability.put(id, table.xpForLevel(lvl));
        }
    }

    /** Bump a capability by a number of <em>levels</em> (profession claim gives +3 levels). */
    public void addLevels(CapabilityId id, int levels) {
        if (levels <= 0) return;
        setLevel(id, levelOf(id) + levels);
    }

    /**
     * Add practice/chat XP to a capability and refresh its last-practised day (resetting the
     * decay clock). {@code today} is the Minecraft day number.
     */
    public void addXp(CapabilityId id, int amount, long today) {
        if (id == null || amount <= 0) return;
        xpByCapability.put(id, xpOf(id) + amount);
        lastPracticedDay.put(id, today);
    }

    /**
     * Apply the rust curve: after {@link #RUST_GRACE_DAYS} free days, XP decays on a
     * slow-start / fast-finish square curve over {@link #RUST_DAYS}. Dropping below a level
     * threshold re-locks whatever that level gated.
     */
    public void decayTo(long today) {
        int rustDays = ModConfig.CAPABILITY_RUST_DAYS.get();
        Iterator<Map.Entry<CapabilityId, Integer>> it = xpByCapability.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CapabilityId, Integer> e = it.next();
            CapabilityId id = e.getKey();
            Long last = lastPracticedDay.get(id);
            if (last == null) continue;
            long effectiveDays = Math.max(0, today - last - RUST_GRACE_DAYS);
            if (effectiveDays <= 0) continue;
            double f = (double) effectiveDays / rustDays;
            double retention = Math.max(0.0, 1.0 - f * f);
            int xp = (int) Math.round(e.getValue() * retention);
            if (xp <= 0) {
                it.remove();
                lastPracticedDay.remove(id);
            } else {
                e.setValue(xp);
            }
        }
    }

    /** Vanilla trade level mirrored from a capability: {@code (level - 1) / 2}, capped at 5. */
    public int vanillaLevelFor(CapabilityId id) {
        int lvl = levelOf(id);
        return Math.max(0, Math.min(5, (lvl - 1) / 2));
    }

    // ── NBT ──
    @Override
    public CompoundNBT serializeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        ListNBT list = new ListNBT();
        for (Map.Entry<CapabilityId, Integer> e : xpByCapability.entrySet()) {
            CompoundNBT c = new CompoundNBT();
            c.putString("Capability", e.getKey().name().toLowerCase(java.util.Locale.ROOT));
            c.putInt("xp", e.getValue());
            Long d = lastPracticedDay.get(e.getKey());
            c.putLong("day", d == null ? -1L : d);
            list.add(c);
        }
        nbt.put("Capabilities", list);
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundNBT nbt) {
        xpByCapability.clear();
        lastPracticedDay.clear();
        if (!nbt.contains("Capabilities")) return;
        ListNBT list = nbt.getList("Capabilities", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT c = list.getCompound(i);
            CapabilityId id = CapabilityId.fromId(c.getString("Capability"));
            if (id == null) continue;
            int xp = c.getInt("xp");
            long day = c.contains("day") ? c.getLong("day") : -1L;
            if (xp > 0) xpByCapability.put(id, xp);
            if (day >= 0) lastPracticedDay.put(id, day);
        }
    }
}
