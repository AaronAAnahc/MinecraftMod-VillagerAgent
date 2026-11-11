package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.Failure;
import com.github.AaronAA0721.villageragent.ai.behavior.FailureReason;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;

/** Failure recovery retains an acquisition objective; its skill compares purchase and craft costs. */
public final class FailureRecoveryPlanner {
    private static final Logger LOGGER = LogManager.getLogger();

    private FailureRecoveryPlanner() {}

    /**
     * Entry point — called by {@code processGoals} after a skill returns BLOCKED/FAIL or when no
     * skill handles a goal. If the failed goal recorded a {@link Failure}, dispatch the matching
     * recovery. Safe to call every tick: it only acts when a failure is recorded, and item
     * recovery de-duplicates via existing-goal scan rather than per-tick re-injection.
     */
    public static void handle(VillagerAgentData agent, AgentGoal failedGoal,
                              VillagerEntity villager, ServerWorld world) {
        Failure f = failedGoal.getLastFailure();
        if (f == null) return; // nothing to recover from

        if (f.reason.isCapabilityGap()) {
            // Capability too low — cannot recover via items; the villager must level up first.
            agent.addMemory("Stalled on " + failedGoal.getGoalType()
                    + ": need " + f.capability + " level " + f.requiredLevel
                    + " (will retry after training)");
            return;
        }
        if (f.reason == FailureReason.NO_JOB_SITE) {
            // The villager's workstation vanished (destroyed) mid-goal. Recovery = find a new one.
            recoverJobSite(agent, failedGoal, villager, world);
            return;
        }
        if (f.reason == FailureReason.NO_RECIPE) {
            // Acquisition can still use a live seller or cooperative extraction source.
            String itemId = f.itemId != null ? f.itemId : failedGoal.getTargetItem();
            if (itemId != null && !itemId.isEmpty()) {
                recoverMissingItem(agent, villager, world, itemId, Math.max(1, f.quantity));
            } else {
                agent.addMemory("Stalled on " + failedGoal.getGoalType() + ": " + f.reason);
            }
            return;
        }
        if (!f.reason.isItemRecoverable()) {
            // NO_BED / BED_OCCUPIED / NOT_NIGHT / UNREACHABLE / UNKNOWN
            agent.addMemory("Stalled on " + failedGoal.getGoalType() + ": " + f.reason);
            return;
        }

        // MISSING_ITEM — retain the acquisition objective for method comparison.
        String missingItem = f.itemId;
        if ("minecraft:iron_hoe".equals(missingItem)
                && ("farm".equals(failedGoal.getGoalType()) || "till".equals(failedGoal.getGoalType()))) {
            // Field work accepts any hoe. Prefer a known tool that current stock can actually produce.
            for (String hoe : new String[] {"minecraft:iron_hoe", "minecraft:stone_hoe", "minecraft:wooden_hoe"}) {
                if (!agent.getUnlockedRecipes().contains(hoe)
                        && !SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), hoe)) continue;
                net.minecraft.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(hoe));
                if (CraftingPlan.build(world, agent.getInventory(), item, 1).complete) { missingItem = hoe; break; }
            }
        }
        recoverMissingItem(agent, villager, world, missingItem, f.quantity);
    }

    /**
     * Recover from a lost job site: inject a {@code find_job_site} goal (or boost one already
     * queued) so the villager walks to a new workstation and re-claims it. One-shot — it drops out
     * once the site is claimed, at which point the routine restock/craft picks back up.
     */
    private static void recoverJobSite(VillagerAgentData agent, AgentGoal failedGoal,
                                       VillagerEntity villager, ServerWorld world) {
        for (AgentGoal g : agent.getGoals()) {
            if (!g.isCompleted() && "find_job_site".equals(g.getGoalType())) {
                boost(agent, g);
                return;
            }
        }
        AgentGoal g = new AgentGoal("find_job_site",
                "Find a new " + agent.getProfession() + " workstation", 7);
        g.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        g.setTargetProfession(agent.getProfession());
        agent.getGoals().add(g);
        BrainLog.goalAdded(agent, g, "recovery:find_job_site");
        LOGGER.info("{} recovery: queued find_job_site (lost {})", agent.getName(), failedGoal.getGoalType());
    }

    /** Public entry: ensure one acquisition objective holding at least {@code desiredStock} of the item. */
    public static AgentGoal requireItem(VillagerAgentData agent, String itemId, int desiredStock) {
        if (itemId == null || itemId.isEmpty()) return null;
        int have = com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, itemId);
        if (desiredStock <= have) return null;
        recoverMissingItem(agent, null, null, itemId, desiredStock - have);
        return findGoalByTarget(agent, "acquire_item", itemId);
    }

    /** Reuse or create an inventory-target acquisition goal. */
    private static void recoverMissingItem(VillagerAgentData agent, VillagerEntity villager,
                                           ServerWorld world, String itemId, int qty) {
        if (itemId == null || itemId.isEmpty()) return;
        // Work skills report a missing tool by category ("axe", "pickaxe"): any tier will do.
        if (SupplyPolicy.FOOD.equals(itemId)) return; // eat runs its own #food acquisition
        if (SupplyPolicy.isToolCategory(itemId)) {
            recoverTool(agent, world, itemId);
            return;
        }
        // Nobody in the village can make it (glass, paper, string...): asking would only post a dead request.
        if (world != null && !VillageProducible.producible(world, itemId)) {
            BrainLog.diagnostic(agent, "recovery-unproducible", "item=" + itemId);
            return;
        }

        // Wood is interchangeable: a missing oak log is "any log", a missing oak plank "any planks".
        net.minecraft.item.Item item = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(itemId);
        String material = SupplyPolicy.materialCategory(item);
        AgentGoal existing = findGoalByTarget(agent, "acquire_item", itemId);
        if (existing == null && material != null)
            for (AgentGoal g : agent.getGoals())
                if (!g.isCompleted() && "acquire_item".equals(g.getGoalType()) && material.equals(g.getRequestCategory())) existing = g;
        int held = material != null
                ? SupplyPolicy.units(SupplyPolicy.Spec.category(material), agent.getInventory().getItems())
                : com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, itemId);
        int desired = held + Math.max(1, qty);
        if (existing != null) {
            existing.setTargetQuantity(Math.max(existing.getTargetQuantity(), desired));
            boost(agent, existing);
            return;
        }
        AgentGoal goal = new AgentGoal("acquire_item", "Acquire " + (material != null ? "any " + material : itemId)
                + " (recovery)", 6);
        goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        goal.setTargetItem(itemId); goal.setTargetQuantity(desired);
        if (material != null) goal.setRequestCategory(material);
        goal.setOutcomeItems(Collections.singleton(itemId));
        agent.getGoals().add(goal);
        BrainLog.goalAdded(agent, goal, "recovery:acquire:" + itemId);
    }

    /** One category acquisition for a missing work tool, targeting the profession's configured tool. */
    private static void recoverTool(VillagerAgentData agent, ServerWorld world, String category) {
        String item = null;
        for (String id : VillagerEconomySystem.essentialItems(agent.getProfession())) {
            net.minecraft.item.Item it = com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(id);
            if (category.equals(SupplyPolicy.toolCategory(it))) { item = id; break; }
        }
        if (item == null) item = "minecraft:stone_" + category;
        for (AgentGoal g : agent.getGoals()) {
            if (g.isCompleted() || !"acquire_item".equals(g.getGoalType())) continue;
            if (category.equals(g.getRequestCategory()) || item.equals(g.getTargetItem())) { boost(agent, g); return; }
        }
        if (world != null && !VillageProducible.producible(world, item)) {
            BrainLog.diagnostic(agent, "recovery-unproducible", "item=" + item + " category=" + category);
            return;
        }
        AgentGoal goal = new AgentGoal("acquire_item", "Acquire profession tool " + category, 6);
        goal.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
        goal.setTargetItem(item); goal.setTargetQuantity(1);
        goal.setRequestCategory(category);
        goal.setOutcomeItems(Collections.singleton(item));
        agent.getGoals().add(goal);
        BrainLog.goalAdded(agent, goal, "recovery:tool:" + category);
    }

    /** Clear temporary recovery cooldown without changing priority. */
    private static void boost(VillagerAgentData agent, AgentGoal g) {
        int before = g.getPriority();
        // Recovery is a temporary opportunity, not a permanent emergency.
        g.setCooldownUntilTick(0);
        BrainLog.goalState(agent, g, "boosted " + before + "->" + g.getPriority(), "recovery");
    }

    /** First non-completed goal of {@code type} whose targetItem matches, or null. */
    private static AgentGoal findGoalByTarget(VillagerAgentData agent, String type, String targetItem) {
        for (AgentGoal g : agent.getGoals()) {
            if (g == null || g.isCompleted()) continue;
            if (type.equals(g.getGoalType()) && targetItem.equals(g.getTargetItem())) return g;
        }
        return null;
    }

}
