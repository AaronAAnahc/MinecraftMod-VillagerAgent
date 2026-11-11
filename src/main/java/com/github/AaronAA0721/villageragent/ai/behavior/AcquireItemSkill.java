package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
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
import java.util.List;

/**
 * Acquisition delegates method selection to the shared executor. When all methods
 * are exhausted (WAITING), the request_supply method has already posted a free village request and blocks itself behind a
 * child dependency so the goal list can make progress on other work instead of
 * re-polling this goal every cooldown cycle.
 */
public final class AcquireItemSkill implements VillagerSkill {
    private static final Logger LOGGER = LogManager.getLogger("VillagerBrain");
    @Override public boolean isComposite() { return true; }
    @Override public String id() { return "acquire_item"; }
    @Override public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "acquire_item".equals(goal.getGoalType()) && goal.getTargetItem() != null && !goal.getTargetItem().isEmpty();
    }

    /**
     * Scheduling filter — called every tick by GoalScheduler.utility().
     * 1) If the item is already obtained → mark complete, return 0.
     * 2) If a child goal (craft/trade) is still active → return 0 (skip, let the child run).
     * 3) Otherwise → return 1.0 (normal scheduling weight).
     */
    @Override
    public double chanceFactor(VillagerAgentData agent, AgentGoal goal, long now) {
        // Check if target item already satisfied.
        ResourceLocation id = ResourceLocation.tryParse(goal.getTargetItem());
        if (id != null) {
            Item target = ForgeRegistries.ITEMS.getValue(id);
            if (target != null && target != Items.AIR) {
                if (held(agent, goal, target) >= Math.max(1, goal.getTargetQuantity())) {
                    goal.setCompleted(true);
                    return 0;
                }
            }
        }
        // If a child dependency is active and that child still exists in the goal list, skip.
        if (goal.hasChildDependency()) {
            if (hasActiveChild(agent, goal)) return 0;
            // Child finished or was removed — clear the dependency so we can re-evaluate.
            goal.clearChildDependency();
        }
        return 1.0;
    }

    @Override public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        ResourceLocation id = ResourceLocation.tryParse(goal.getTargetItem());
        Item target = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (target == null || target == Items.AIR) return SkillResult.DELETE;
        int missing = Math.max(1, goal.getTargetQuantity()) - held(agent, goal, target);
        if (missing <= 0) {
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }
        // A booked delivery is a subtask of this acquisition's request method; keep descending into it.
        if (goal.getOutcomeItems().isEmpty()) goal.setOutcomeItems(Collections.singleton(id.toString()));

        // Delegate to the method executor (craft / buy / produce / extract).
        SkillResult result = agent.brain().acquisitions.execute(agent, villager, world, goal, target, Math.max(1, goal.getTargetQuantity()));

        // If the executor has no viable method (WAITING with nothing to do), check whether
        // any acquisition method exists at all. If not, FAIL immediately instead of spinning.
        if (result == SkillResult.WAITING) {
            // Requests are published only by the request_supply method after a physical board visit.
            AcquisitionMethods.Context ctx = new AcquisitionMethods.Context(
                    agent, villager, world, target, Math.max(1, goal.getTargetQuantity()), false);
            List<TaskMethod<AcquisitionMethods.Context>> candidates = AcquisitionMethods.candidates(ctx);
            if (candidates.isEmpty()) {
                // No method can produce this item for this villager — give up.
                LOGGER.info("{} | DIAG | acquire-no-methods | item={} profession={} capabilities={}",
                        agent.getName(), id, agent.getProfession(),
                        agent.getCapabilityPool().snapshot());
                return SkillResult.FAIL;
            }
            // Check if any craft/trade child could fulfill this — look for existing goals.
            if (!goal.hasChildDependency()) {
                AgentGoal producer = findProducerGoal(agent, id.toString());
                if (producer != null) {
                    goal.setChildDependency(producer.getGoalType(), producer.getTargetItem());
                }
            }
            // Use a longer minimum exit cooldown so we don't spin. Waiting on a posted request takes longer still:
            // the delivery arrives through its own meet_trade goal, so re-checking often achieves nothing.
            AcquisitionMethods.Context waiting = new AcquisitionMethods.Context(
                    agent, villager, world, target, Math.max(1, goal.getTargetQuantity()), false);
            waiting.requestCategory = category(goal);
            boolean onRequest = OwnRequests.covering(waiting, world.getGameTime()) != null;
            goal.setMinimumExitCooldownTicks(onRequest ? 1200 : 400);
            BrainLog.diagnostic(agent, "acquire-waiting", "item=" + id + " onRequest=" + onRequest
                    + " retryIn=" + (onRequest ? 1200 : 400));
        } else {
            // Active method is running — use normal cooldown.
            goal.setMinimumExitCooldownTicks(0);
        }
        return result;
    }

    private static String category(AgentGoal goal) {
        String c = goal.getRequestCategory();
        return c != null && SupplyPolicy.isCategory(c) ? c : null;
    }
    /** Exact item count, or units of any matching item when the goal is a generic category requirement. */
    private static int held(VillagerAgentData agent, AgentGoal goal, Item target) {
        String c = category(goal);
        return c == null ? agent.getInventory().countItem(new ItemStack(target))
                : SupplyPolicy.units(SupplyPolicy.Spec.category(c), agent.getInventory().getItems());
    }

    /** True if the child goal referenced by the dependency still exists and is not completed. */
    private static boolean hasActiveChild(VillagerAgentData agent, AgentGoal parent) {
        String childType = parent.getChildGoalType();
        String childItem = parent.getChildTargetItem();
        if (childType == null) return false;
        for (AgentGoal g : agent.getGoals()) {
            if (g == parent || g.isCompleted()) continue;
            if (childType.equals(g.getGoalType())
                    && (childItem == null || childItem.equals(g.getTargetItem()))) return true;
        }
        return false;
    }

    /** Find an existing non-completed goal that produces the given item. */
    private static AgentGoal findProducerGoal(VillagerAgentData agent, String itemId) {
        for (AgentGoal g : agent.getGoals()) {
            if (g.isCompleted()) continue;
            if (g.produces(itemId) && !"acquire_item".equals(g.getGoalType())) return g;
            // A craft/fulfill_order goal targeting this item counts as a producer.
            if (("craft".equals(g.getGoalType()) || "fulfill_order".equals(g.getGoalType()))
                    && itemId.equals(g.getTargetItem())) return g;
        }
        return null;
    }
}
