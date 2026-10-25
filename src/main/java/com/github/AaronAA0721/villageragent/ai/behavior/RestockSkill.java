package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Replenishment requests actual goods; visiting a workstation no longer creates free stock. */
public final class RestockSkill implements VillagerSkill {
    public boolean isComposite() { return true; }
    public String id() { return "restock"; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "restock".equals(g.getGoalType()) && JobStarterItems.hasStarterItems(a.getProfession());
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld world) {
        if (a.brain().tasks.current().child() != null) {
            SkillResult result = BehaviorExecutor.executeChild(a, v, world, () -> { throw new IllegalStateException("Existing craft child expected"); });
            return result == SkillResult.SUCCESS ? SkillResult.RUNNING : result;
        }
        boolean missing = false;
        for (JobStarterItems.StarterItem supply : JobStarterItems.getStarterItems(a.getProfession())) {
            int desired = Math.min(8, supply.quantity);
            int have = a.getInventory().countItem(new ItemStack(supply.item));
            if (have >= desired) continue;
            missing = true;
            String id = supply.item.getRegistryName().toString();
            if (CraftingPlan.supports(world, supply.item) && (a.getUnlockedRecipes().contains(id)
                    || SkillCraftCatalog.isUnlockedFor(a.getCapabilityPool(), a.getProfession(), id))) {
                SkillResult result = BehaviorExecutor.executeChild(a, v, world, () -> {
                    AgentGoal child = new AgentGoal("craft", "Make missing supply " + id, 6);
                    child.setTargetItem(id); child.setTargetQuantity(desired - have); return child;
                });
                return result == SkillResult.SUCCESS ? SkillResult.RUNNING : result;
            }
            ResourceWorkSystem.request(world, a, id, desired);
            VillageSignalBoard.get().post(a.getVillagerId(), v.blockPosition(), VillageSignalBoard.MsgType.TRADE_WANT,
                    supply.item.getRegistryName(), desired - have, world.getGameTime());
        }
        return missing ? SkillResult.WAITING : SkillResult.SUCCESS;
    }
    public static Set<String> outcomeFor(String profession) {
        Set<String> items = new HashSet<>();
        for (JobStarterItems.StarterItem supply : JobStarterItems.getStarterItems(profession))
            if (supply.item != null && supply.item.getRegistryName() != null) items.add(supply.item.getRegistryName().toString());
        return items;
    }
}
