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

/**
 * "acquire_item" goal: get a specific item into the villager's inventory, by any means.
 *
 * <p>This is the execution half of the failure-driven recovery chain
 * ({@code docs/失败驱动重规划.md} §5.3). The <em>planner} ({@link
 * com.github.AaronAA0721.villageragent.ai.FailureRecoveryPlanner}) decides that an item is missing
 * and broadcasts a {@code TRADE_WANT}; this skill is what actually completes the acquisition:
 * <ol>
 *   <li><b>Try to craft</b> — if a recipe resolves and the villager's capability pool unlocks it,
 *       consume materials and produce the item right away (no workstation walk — the villager
 *       "improvises" with what's on hand; workstation-bound production is {@link CraftSkill}'s
 *       job). If materials are short, the planner has already posted TRADE_WANTs for them; this
 *       skill just waits and retries.</li>
 *   <li><b>Wait for a trade</b> — if the item has no recipe or the villager can't craft it, the
 *       TRADE_WANT the planner posted is the only path. Another villager's economy scan or a
 *       player fulfils it; this skill polls the inventory every tick until the item arrives.</li>
 * </ol>
 *
 * <p>On success the skill retires every {@code TRADE_WANT} it (the same villager) posted for this
 * acquisition — both the item itself and its missing materials — so the village board is not left
 * advertising a satisfied demand.
 *
 * <p>The skill is universal (no capability gate): even a villager who can't craft the item
 * themselves can still <em>acquire} it via trade, so the goal must stay runnable while waiting.
 */
public class AcquireItemSkill implements VillagerSkill {
    private static final Logger LOGGER = LogManager.getLogger();

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

        // ── Step 2: try to craft it on the spot ──
        ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, itemId);
        if (recipe != null
                && SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), itemId)) {
            // consumeAndProduce verifies materials first and consumes only if all present.
            boolean ok = recipe.consumeAndProduce(agent.getInventory(), qty);
            if (ok) {
                agent.addMemory("Crafted " + qty + "x " + itemId + " to fill an order");
                LOGGER.info("{} crafted {} x{} via acquire_item", agent.getName(), itemId, qty);
                // Next tick step 1 will detect the item in inventory and retire the trade wants.
                return SkillResult.RUNNING;
            }
            // Yield the body while supplies are being acquired.
            return SkillResult.WAITING;
        }

        // ── Step 3: no craft path — wait for a trade to fulfil the TRADE_WANT ──
        // (The planner posted TRADE_WANT(itemId) when it injected this goal.) Nothing to do
        // this tick but wait; the economy system / a player will supply it.
        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, itemId, qty);
        return SkillResult.WAITING;
    }
}
