package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.NativeRecipeResolver;
import com.github.AaronAA0721.villageragent.ai.ResolvedRecipe;
import com.github.AaronAA0721.villageragent.ai.SkillCraftCatalog;
import com.github.AaronAA0721.villageragent.ai.VillageSignalBoard;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;

/** Acquire existing stock or delegate known recipes to the same workstation-aware craft skill. */
public class AcquireItemSkill implements VillagerSkill {
    private static final Logger LOGGER = LogManager.getLogger();

    @Override public boolean isComposite() { return true; }
    @Override public String id() { return "acquire_item"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!"acquire_item".equals(goal.getGoalType())) return false;
        return goal.getTargetItem() != null && !goal.getTargetItem().isEmpty();
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String itemId = goal.getTargetItem();
        int qty = Math.max(1, goal.getTargetQuantity());

        // Lazy-fill outcome (defensive; planner sets it at injection).
        if (goal.getOutcomeItems().isEmpty()) {
            goal.setOutcomeItems(Collections.singleton(itemId));
        }

        // ── Step 1: do we already have enough? ──
        Item target = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (target == null || target == Items.AIR) {
            agent.addMemory("Unknown item to acquire: " + itemId);
            goal.setLastFailure(FailureReason.NO_RECIPE.with(itemId));
            goal.setCompleted(true);
            return SkillResult.FAIL;
        }
        int have = agent.getInventory().countItem(new ItemStack(target));
        if (have >= qty) {
            // Acquired! Retire every TRADE_WANT this villager posted for this acquisition — both
            // the item itself and any materials the planner recursed on.
            VillageSignalBoard.get().removeByAuthor(
                    villager.getUUID(), VillageSignalBoard.MsgType.TRADE_WANT, null);
            agent.addMemory("Acquired " + qty + "x " + itemId);
            LOGGER.info("{} acquired {} x{}", agent.getName(), itemId, qty);
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        // Step 2: delegate crafting, including intermediate materials and table travel.
        if (NativeRecipeResolver.resolveRecipe(world, itemId) != null && (agent.getUnlockedRecipes().contains(itemId)
                || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), itemId))) {
            SkillResult result = BehaviorExecutor.executeChild(agent, villager, world, () -> {
                AgentGoal child = new AgentGoal("craft", "Craft missing " + itemId, 6);
                child.setTargetItem(itemId);
                child.setTargetQuantity(Math.max(1, qty - agent.getInventory().countItem(new ItemStack(target))));
                return child;
            });
            return result == SkillResult.SUCCESS ? SkillResult.RUNNING : result;
        }

        // ── Step 3: no craft path — wait for a trade to fulfil the TRADE_WANT ──
        // (The planner posted TRADE_WANT(itemId) when it injected this goal.) Nothing to do
        // this tick but wait; the economy system / a player will supply it.
        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, itemId, qty);
        return SkillResult.WAITING;
    }
}
