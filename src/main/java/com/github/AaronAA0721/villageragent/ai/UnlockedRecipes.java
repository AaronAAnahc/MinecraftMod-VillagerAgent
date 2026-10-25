package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityPool;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-villager set of craftable items, derived from {@link SkillCraftCatalog} + the villager's
 * {@link CapabilityPool}.
 *
 * <p>Starts empty. On a capability level-up the set is refreshed — every recipe whose
 * {@code requiredCapability} is met at the new level is added; on a level drop (rust decay) recipes
 * whose {@code requiredLevel} now exceeds the current level are removed ("relock on level loss"). The set is
 * stored on {@code VillagerAgentData} (NBT) so it survives restarts.
 */
public class UnlockedRecipes {

    /** itemId (registry string) -> the capability level that unlocked it. */
    private final Map<String, Integer> recipes = new HashMap<>();

    public boolean contains(String itemId) { return recipes.containsKey(itemId); }

    public int requiredLevelOf(String itemId) {
        Integer l = recipes.get(itemId);
        return l == null ? 0 : l;
    }

    public Set<String> ids() { return recipes.keySet(); }

    public boolean isEmpty() { return recipes.isEmpty(); }

    /** Add a recipe unlocked at the given capability level (idempotent). */
    public void add(String itemId, int requiredLevel) {
        recipes.put(itemId, requiredLevel);
    }

    /** Drop every recipe whose required level now exceeds {@code level} (used on decay). */
    public void removeAbove(int level) {
        recipes.entrySet().removeIf(e -> e.getValue() > level);
    }

    /**
     * Rebuild the set from the catalog against the villager's current capability levels. Call on
     * any capability change (level up via practice/chat, level down via decay) so the set always
     * matches what the villager can actually make.
     */
    public void refresh(CapabilityPool pool, String profession) {
        recipes.clear();
        for (String itemId : SkillCraftCatalog.getUnlockedIds(pool, profession)) {
            recipes.put(itemId, SkillCraftCatalog.requiredLevelFor(profession, itemId));
        }
    }

    // ── NBT ──
    public CompoundNBT writeNBT() {
        CompoundNBT nbt = new CompoundNBT();
        ListNBT list = new ListNBT();
        for (Map.Entry<String, Integer> e : recipes.entrySet()) {
            CompoundNBT c = new CompoundNBT();
            c.putString("Item", e.getKey());
            c.putInt("Level", e.getValue());
            list.add(c);
        }
        nbt.put("Unlocked", list);
        return nbt;
    }

    public static UnlockedRecipes readNBT(CompoundNBT nbt) {
        UnlockedRecipes r = new UnlockedRecipes();
        if (nbt.contains("Unlocked")) {
            ListNBT list = nbt.getList("Unlocked", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundNBT c = list.getCompound(i);
                r.recipes.put(c.getString("Item"), c.getInt("Level"));
            }
        }
        return r;
    }
}
