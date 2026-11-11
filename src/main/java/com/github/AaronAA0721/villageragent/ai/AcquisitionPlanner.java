package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/**
 * Crafting feasibility from the villager's own simulated inventory. Cooperative supply is free, so no
 * purchase basket or affordability is evaluated here; missing inputs become request-backed dependencies
 * through {@code craft_inputs} instead.
 */
public final class AcquisitionPlanner {
    public static final class Choice {
        public final List<VillageSignalBoard.VillageMessage> offers;
        public final int[] quantities;
        public final int buyerCost;
        Choice(List<VillageSignalBoard.VillageMessage> offers, int[] quantities, int buyerCost) {
            this.offers = offers; this.quantities = quantities; this.buyerCost = buyerCost;
        }
        public boolean needsPurchase() { return Arrays.stream(quantities).anyMatch(q -> q > 0); }
    }

    public static Choice choose(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                Item target, int desired, boolean crafting) {
        if (!crafting) return null;
        String id = target.getRegistryName().toString();
        boolean craftable = CraftingPlan.supports(world, target) && (agent.getUnlockedRecipes().contains(id)
                || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), id));
        if (!craftable) return null;
        AgentInventory trial = new AgentInventory();
        for (int i = 0; i < agent.getInventory().getItems().size(); i++)
            trial.setStackInSlot(i, agent.getInventory().getStackInSlot(i).copy());
        int shortage = desired - trial.countItem(new ItemStack(target));
        if (shortage <= 0) return new Choice(Collections.emptyList(), new int[0], 0);
        // A missing crafting table is not a reason to rule crafting out: CraftSkill obtains (crafts) and places
        // one on its own when a 3x3 step needs it.
        CraftingPlan plan = CraftingPlan.build(world, trial, target, shortage);
        if (!plan.complete) return null;
        return new Choice(Collections.emptyList(), new int[0], 0);
    }
    /** A remembered crafting table, or one carried (CraftSkill places a carried table when it needs one). */
    public static boolean hasCraftingStation(VillagerAgentData agent) {
        if (!agent.findWorkbenches().isEmpty()) return true;
        for (ItemStack stack : agent.getInventory().getItems())
            if (!stack.isEmpty() && stack.getItem() == Items.CRAFTING_TABLE) return true;
        return false;
    }
    private AcquisitionPlanner() {}
}
