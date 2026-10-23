package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor;
import com.github.AaronAA0721.villageragent.ai.behavior.Failure;
import com.github.AaronAA0721.villageragent.ai.behavior.FailureReason;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;

/**
 * The failure-driven re-planner — the half that turns a blocked/failed goal into new sub-goals.
 *
 * <p>Per {@code docs/失败驱动重规划.md} (v2 simplification): <b>missing tool and missing item are
 * the same problem</b>. Both surface as a {@link FailureReason#MISSING_ITEM} with the item's
 * registry name in {@link Failure#itemId}, and both run the <em>same</em> three-step recovery
 * chain:
 * <ol>
 *   <li><b>Find an existing todo that produces the item</b> — scan the agent's goals for one
 *       whose {@link AgentGoal#getOutcomeItems()} contains the missing id <em>and</em> that a skill
 *       can handle right now. If found, boost its priority (capped at 10) so it runs sooner.
 *       This is the de-duplication mechanism: a recovery goal already in the list is prioritised,
 *       not duplicated. (e.g. a {@code restock} goal's outcome includes {@code iron_hoe}, so a
 *       farmer missing a hoe finds restock and boosts it.)</li>
 *   <li><b>Craft it</b> — if a recipe resolves and the villager's capability pool unlocks it,
 *       boost (or inject) a {@code craft} goal for the item.</li>
 *   <li><b>Trade for it</b> — broadcast a {@code TRADE_WANT} on {@link VillageSignalBoard}; if a
 *       recipe exists but <em>materials</em> are missing, recurse on each missing material (depth ≤ 2
 *       to prevent infinite bootstrap chains).</li>
 * </ol>
 *
 * <p>Other {@link FailureReason}s are not item-recoverable (capability gap, no bed, not night…):
 * the planner logs them and leaves the goal blocked — recovery there happens by other means
 * (level-up, dawn, bed construction).
 *
 * <p>The planner is pure deterministic, LLM-free, single-threaded (called from the server thread
 * inside {@code processGoals}). It owns no per-villager state: de-duplication is structural
 * (scan existing goals), so there is nothing to clean up on goal completion.
 */
public final class FailureRecoveryPlanner {
    private static final Logger LOGGER = LogManager.getLogger();

    /** How much a recovery boost raises a goal's priority (capped at 10). */
    private static final int PRIORITY_BOOST = 3;
    /** Max recursion depth when trading for a craft's missing materials (prevents A→B→A loops). */
    private static final int MAX_MATERIAL_DEPTH = 2;

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
            // The villager's workstation vanished (destroyed) mid-goal. Recovery = find a new one,
            // not "give up". This closes the loop that used to leave an employed villager standing
            // still with a dead restock/craft goal (行为生成器统一重构方案 §四).
            recoverJobSite(agent, failedGoal, villager, world);
            return;
        }
        if (f.reason == FailureReason.NO_RECIPE) {
            // No resolvable recipe → crafting is impossible, so trading is the only path. Fall
            // through to the same TRADE_WANT broadcast MISSING_ITEM's step 3 uses. De-dup is
            // structural (the signal board is idempotent per author+item).
            String itemId = f.itemId != null ? f.itemId : failedGoal.getTargetItem();
            if (itemId != null && !itemId.isEmpty()) {
                postTradeWant(villager, agent, itemId, Math.max(1, f.quantity));
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

        // MISSING_ITEM — run the unified three-step chain.
        recoverMissingItem(agent, villager, world, f.itemId, f.quantity, 0);
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

    /**
     * The unified three-step recovery chain for a missing item (tool or material).
     * @param depth current recursion depth (material bootstrap); capped at {@link #MAX_MATERIAL_DEPTH}.
     */
    private static void recoverMissingItem(VillagerAgentData agent, VillagerEntity villager,
                                           ServerWorld world, String itemId, int qty, int depth) {
        if (itemId == null || itemId.isEmpty()) return;
        if (depth > MAX_MATERIAL_DEPTH) return;

        // ── Step 1: an existing todo already produces this item & can run → boost it ──
        // This is also the de-duplication: a recovery goal already in the list is prioritised,
        // not duplicated. A completed restock goal from earlier today is skipped (it won't
        // re-produce until tomorrow).
        boolean found = false;
        for (AgentGoal g : agent.getGoals()) {
            if (g == null || g.isCompleted()) continue;
            if (g.produces(itemId)) {
                // Only boost if a skill can actually handle it now (e.g. restock goal whose
                // window is active, or a craft goal whose workstation exists).
                if (BehaviorExecutor.findHandler(agent, g, villager, world) != null) {
                    boost(agent, g);
                    found = true;
                }
            }
        }
        if (found) return; // an existing todo will supply the item — just prioritise it

        // ── Step 2: can the villager craft it? ──
        ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, itemId);
        boolean craftable = recipe != null
                && SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), itemId);
        if (craftable) {
            AgentGoal existing = findGoalByTarget(agent, "craft", itemId);
            if (existing != null) {
                boost(agent, existing); // already queued → just prioritise
            } else {
                AgentGoal g = new AgentGoal("craft", "Craft " + itemId + " (recovery)", 6);
                g.setLifecycle(AgentGoal.Lifecycle.ONE_SHOT);
                g.setTargetItem(itemId);
                g.setTargetQuantity(Math.max(1, qty));
                g.setOutcomeItems(Collections.singleton(itemId));
                agent.getGoals().add(g);
                BrainLog.goalAdded(agent, g, "recovery:craft:" + itemId);
                LOGGER.info("{} recovery: queued craft goal for {}", agent.getName(), itemId);
            }
            return; // craft goal will produce the item
        }

        // ── Step 3: trade — broadcast a WANT, and recurse on missing materials ──
        postTradeWant(villager, agent, itemId, qty);
        if (recipe != null) {
            // The item has a recipe but the villager can't craft it (locked / no workstation) —
            // recurse on each material the villager is short of, so the village can supply those
            // directly instead.
            for (String materialId : NativeRecipeResolver.missingMaterials(agent.getInventory(), recipe, qty)) {
                recoverMissingItem(agent, villager, world, materialId, 1, depth + 1);
            }
        } else {
            // No recipe at all (e.g. raw ore, mob drop) — nothing to recurse on; the TRADE_WANT
            // above is the only path. A player or another villager's gather/trade must satisfy it.
            LOGGER.info("{} recovery: no recipe for {} — TRADE_WANT posted only", agent.getName(), itemId);
        }
    }

    /** Raise a goal's priority by {@link #PRIORITY_BOOST}, clamped to the 1..10 band. */
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

    /** Broadcast a TRADE_WANT for {@code itemId} on the village board (idempotent per author+item). */
    private static void postTradeWant(VillagerEntity villager, VillagerAgentData agent,
                                     String itemId, int qty) {
        ResourceLocation rl = new ResourceLocation(itemId);
        long now = villager.level.getGameTime();
        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(
                (ServerWorld) villager.level, agent, itemId,
                com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, itemId) + Math.max(1, qty));
        VillageSignalBoard.get().post(villager.getUUID(), villager.blockPosition(),
                VillageSignalBoard.MsgType.TRADE_WANT, rl, Math.max(1, qty), now);
        LOGGER.info("{} recovery: posted TRADE_WANT for {} x{}", agent.getName(), itemId, qty);
    }
}
