package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Collections;

/** Acquisition delegates all method selection and attempt lifetime to the shared executor. */
public final class AcquireItemSkill implements VillagerSkill {
    @Override public boolean isComposite() { return true; }
    @Override public String id() { return "acquire_item"; }
    @Override public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "acquire_item".equals(goal.getGoalType()) && goal.getTargetItem() != null && !goal.getTargetItem().isEmpty();
    }
    @Override public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        ResourceLocation id = ResourceLocation.tryParse(goal.getTargetItem());
        Item target = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (target == null || target == Items.AIR) return SkillResult.DELETE;
        int missing = Math.max(1, goal.getTargetQuantity()) - agent.getInventory().countItem(new ItemStack(target));
        if (missing <= 0) VillageSignalBoard.get().removeByAuthor(agent.getVillagerId(),
                VillageSignalBoard.MsgType.TRADE_WANT, id);
        else if (TradeCoordinator.busy(agent.getVillagerId())) return SkillResult.WAITING;
        else if (com.github.AaronAA0721.villageragent.config.ModConfig.ENABLE_ECONOMY.get())
            VillagerEconomySystem.postDemand(world, villager, agent, id, missing);
        if (goal.getOutcomeItems().isEmpty()) goal.setOutcomeItems(Collections.singleton(id.toString()));
        return agent.brain().acquisitions.execute(agent, villager, world, goal, target, Math.max(1, goal.getTargetQuantity()));
    }
}
