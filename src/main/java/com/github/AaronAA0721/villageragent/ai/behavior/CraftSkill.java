package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.NativeRecipeResolver;
import com.github.AaronAA0721.villageragent.ai.ResolvedRecipe;
import com.github.AaronAA0721.villageragent.ai.SkillCraftCatalog;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;

/** Standard grid recipes use an inventory-aware dependency plan and remembered crafting tables.
 * Other recipe types retain the existing profession-station production path. */
public class CraftSkill implements VillagerSkill {
    @Override public boolean isComposite() { return true; }
    private static final class PlanState {
        com.github.AaronAA0721.villageragent.ai.CraftingPlan plan;
        int next, workTicks;
        BlockPos table;
    }

    private SkillResult craftGrid(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager,
                                  ServerWorld world, Item target) {
        PlanState state = agent.brain().tasks.current().state(PlanState.class, PlanState::new);
        if (state.plan == null) {
            com.github.AaronAA0721.villageragent.ai.CraftingPlan.unlockDependencies(agent, world);
            state.plan = com.github.AaronAA0721.villageragent.ai.CraftingPlan.build(
                    world, agent.getInventory(), target, Math.max(1, goal.getTargetQuantity()));
            if (!state.plan.complete) {
                for (java.util.Map.Entry<String, Integer> need : state.plan.missing.entrySet()) {
                    com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, need.getKey(),
                        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, need.getKey()) + need.getValue());
                    com.github.AaronAA0721.villageragent.ai.VillageSignalBoard.get().post(agent.getVillagerId(), villager.blockPosition(),
                        com.github.AaronAA0721.villageragent.ai.VillageSignalBoard.MsgType.TRADE_WANT,
                        new ResourceLocation(need.getKey()), need.getValue(), world.getGameTime());
                }
                if (!state.plan.missing.isEmpty()) {
                    java.util.Map.Entry<String, Integer> need = state.plan.missing.entrySet().iterator().next();
                    goal.setLastFailure(FailureReason.MISSING_ITEM.with(need.getKey(), need.getValue()));
                }
                return SkillResult.BLOCKED;
            }
        }
        if (state.next >= state.plan.steps.size()) return SkillResult.SUCCESS;
        com.github.AaronAA0721.villageragent.ai.CraftingPlan.Step step = state.plan.steps.get(state.next);
        if (step.needsTable()) {
            if (state.table != null && (!world.isLoaded(state.table)
                    || !world.getBlockState(state.table).is(net.minecraft.block.Blocks.CRAFTING_TABLE))) {
                state.table = null; state.workTicks = 0; agent.setCurrentAction(null); villager.getNavigation().stop();
            }
            if (state.table == null) {
                com.github.AaronAA0721.villageragent.ai.WorkbenchMemory.observe(villager, world);
                net.minecraft.pathfinding.Path path = com.github.AaronAA0721.villageragent.ai.WorkbenchMemory.path(agent, villager, world);
                if (path == null) {
                    agent.addMemory("Need a reachable crafting table for " + goal.getTargetItem());
                    // Crafting tables are shared facilities, not profession POIs: never request a job site.
                    return SkillResult.WAITING;
                }
                state.table = path.getTarget();
                if (!villager.getNavigation().moveTo(path, VillagerNavigationSupport.WALK_SPEED)) return SkillResult.WAITING;
                VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Walking to remembered crafting table");
                move.setTargetBlockPos(state.table); move.setStandCell(path.getNodePos(path.getNodeCount() - 1)); move.setGoalDriven(true); move.setPhase(VillagerAction.ActionPhase.WALKING);
                agent.setCurrentAction(move);
            }
            if (villager.blockPosition().distSqr(state.table) > 4) {
                if (villager.getNavigation().isDone()) {
                    state.table = null;
                    return SkillResult.WAITING;
                }
                return SkillResult.RUNNING;
            }
            villager.getNavigation().stop();
        }
        if (agent.getCurrentAction() == null || agent.getCurrentAction().getActionType() != VillagerAction.ActionType.CRAFT) {
            VillagerAction work = new VillagerAction(VillagerAction.ActionType.CRAFT, "Crafting " + step.recipe.getId());
            work.setGoalDriven(true); work.setPhase(VillagerAction.ActionPhase.ACTING); agent.setCurrentAction(work);
        }
        if (++state.workTicks < 20) return SkillResult.RUNNING;
        if (!step.apply(agent.getInventory(), world)) {
            // Inventory changed or capacity was lost: discard only the unexecuted plan and retry later.
            return SkillResult.WAITING;
        }
        com.github.AaronAA0721.villageragent.ai.BrainLog.diagnostic(agent, "craft-step", "recipe=" + step.recipe.getId());
        if (step.recipe.getResultItem().getItem() == target && goal.getLifecycle() != AgentGoal.Lifecycle.REPEATABLE)
            goal.setTargetQuantity(Math.max(0, goal.getTargetQuantity() - step.recipe.getResultItem().getCount()));
        state.next++; state.workTicks = 0; agent.setCurrentAction(null);
        if (state.next == state.plan.steps.size()) {
            if (SkillCraftCatalog.canEnchant(agent.getProfession(), villager.getVillagerData().getLevel(), goal.getTargetItem()))
                VillagerAgentManager.enchantFirstStack(agent, target, villager);
            agent.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), villager, world.getGameTime());
            com.github.AaronAA0721.villageragent.ai.VillagerEquipmentHelper.refreshEquipment(villager, agent);
            return SkillResult.SUCCESS;
        }
        return SkillResult.RUNNING;
    }


    @Override public String id() { return "craft"; }

    // NOTE: no requiredCapability()/minLevel() gate here on purpose. Whether a villager can craft
    // an item is decided by its UNLOCKED RECIPES (the per-skill unlock table in craft_unlocks.json
    // ∪ the built-in catalog), NOT by a generic CRAFTING level — a Farmer with FARMING=3 can make
    // bread even with CRAFTING=0. The old CRAFTING>=1 gate silently starved farmers of their own
    // profession recipes.

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!"craft".equals(goal.getGoalType())) return false;
        String itemId = goal.getTargetItem();
        if (itemId == null || itemId.isEmpty()) return false;
        // Gate on the unlocked craftable set (code-judged; see SkillCraftCatalog).
        return agent.getUnlockedRecipes().contains(itemId)
                || SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), itemId);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String itemId = goal.getTargetItem();
        if (itemId == null || itemId.isEmpty()) { agent.addMemory("Craft goal has no target item — dropping it"); return SkillResult.DELETE; }

        // Gate on the unlocked craftable set (JSON skill table ∪ built-in catalog) — never trust
        // the LLM. (No vanilla villager-level check: unlocks are capability-driven.)
        if (!agent.getUnlockedRecipes().contains(itemId)
                && !SkillCraftCatalog.isUnlockedFor(agent.getCapabilityPool(), agent.getProfession(), itemId)) {
            agent.addMemory("I don't know how to make " + itemId + " yet — dropping the goal");
            return SkillResult.DELETE;
        }

        Item target = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (target == null || target == Items.AIR) {
            agent.addMemory("Unknown item: " + itemId + " — dropping the goal");
            return SkillResult.DELETE;
        }
        if (com.github.AaronAA0721.villageragent.ai.CraftingPlan.supports(world, target))
            return craftGrid(agent, goal, villager, world, target);
        ResolvedRecipe recipe = NativeRecipeResolver.resolveRecipe(world, target);
        if (recipe == null) {
            // No vanilla / custom recipe exists for this item — the goal can never be satisfied.
            // Delete it outright instead of faking success (a lingering "craft wheat_seeds" goal
            // would otherwise sit in the list forever and starve real goals of the running slot).
            agent.addMemory("No known recipe for " + itemId + " — dropping the goal");
            return SkillResult.DELETE;
        }

        java.util.Map<String, Integer> shortages = NativeRecipeResolver.missingMaterialQuantities(
                agent.getInventory(), recipe, Math.max(1, goal.getTargetQuantity()));
        if (!shortages.isEmpty()) {
            for (java.util.Map.Entry<String, Integer> shortage : shortages.entrySet()) {
                com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, shortage.getKey(),
                    com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, shortage.getKey()) + shortage.getValue());
            }
            java.util.Map.Entry<String, Integer> first = shortages.entrySet().iterator().next();
            goal.setLastFailure(FailureReason.MISSING_ITEM.with(first.getKey(), first.getValue()));
            return SkillResult.BLOCKED;
        }

        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && !cur.isGoalDriven()) { agent.setCurrentAction(null); cur = null; }

        // Phase A: walking to the job site
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
            BlockPos t = cur.getTargetBlockPos();
            if (t == null) { agent.setCurrentAction(null); return SkillResult.RUNNING; }
            if (villager.blockPosition().distSqr(t) <= VillagerAgentManager.GOAL_ARRIVE_SQ) {
                VillagerAction work = new VillagerAction(VillagerAction.ActionType.CRAFT,
                        "Working at " + agent.getProfession() + " station making " + itemId);
                work.setTargetBlockPos(t);
                work.setPhase(VillagerAction.ActionPhase.ACTING);
                work.setGoalDriven(true);
                work.setTargetItem(itemId);
                agent.setCurrentAction(work);
                villager.getNavigation().stop();
                return SkillResult.RUNNING;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > VillagerAgentManager.GOAL_STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach my workstation to craft " + itemId);
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                goal.setLastFailure(FailureReason.UNREACHABLE.with(t.toString()));
                return SkillResult.FAIL;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
            }
            return SkillResult.RUNNING;
        }

        // Phase B: working at the bench
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.CRAFT && cur.isGoalDriven()) {
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() >= VillagerAgentManager.CRAFT_WORK_TICKS) {
                int qty = Math.max(1, goal.getTargetQuantity());
                boolean ok = recipe.consumeAndProduce(agent.getInventory(), qty);
                if (ok && SkillCraftCatalog.canEnchant(agent.getProfession(),
                        villager.getVillagerData().getLevel(), itemId)) {
                    VillagerAgentManager.enchantFirstStack(agent, target, villager);
                }
                if (ok) {
                    agent.addMemory("Crafted " + qty + "x " + itemId + " at my workstation");
                    // Smithing professions train SMITHING; everyone else trains generic CRAFTING.
                    CapabilityId craftCap = CapabilityId.mainForProfession(agent.getProfession()) == CapabilityId.SMITHING
                            ? CapabilityId.SMITHING : CapabilityId.CRAFTING;
                    agent.gainCapabilityXp(craftCap, ModConfig.CAPABILITY_PRACTICE_XP.get(),
                            villager, world.getGameTime());
                    goal.setCompleted(true);
                    agent.setCurrentAction(null);
                    return SkillResult.SUCCESS;
                } else {
                    // Materials insufficient — report the first missing material so the failure-
                    // recovery planner can boost a restock/craft/trade goal for it. Don't complete
                    // the goal; return BLOCKED so the planner acts before the goal is dropped.
                    List<String> missing = NativeRecipeResolver.missingMaterials(agent.getInventory(), recipe, qty);
                    String missingId = missing.isEmpty() ? itemId : missing.get(0);
                    goal.setLastFailure(FailureReason.MISSING_ITEM.with(missingId));
                    agent.addMemory("Tried to craft " + itemId + " but ran out of " + missingId);
                    agent.setCurrentAction(null);
                    return SkillResult.BLOCKED;
                }
            }
            return SkillResult.RUNNING;
        }

        // Phase C: start — navigate to the job site
        if (cur != null) return SkillResult.RUNNING;
        Optional<GlobalPos> job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (!job.isPresent()) {
            agent.addMemory("Wanted to craft but have no workstation");
            goal.setLastFailure(FailureReason.NO_JOB_SITE.with((String) null));
            return SkillResult.BLOCKED;
        }
        BlockPos jobPos = job.get().pos();
        if (villager.blockPosition().distSqr(jobPos) <= VillagerAgentManager.GOAL_ARRIVE_SQ) {
            VillagerAction work = new VillagerAction(VillagerAction.ActionType.CRAFT,
                    "Working at " + agent.getProfession() + " station making " + itemId);
            work.setTargetBlockPos(jobPos);
            work.setPhase(VillagerAction.ActionPhase.ACTING);
            work.setGoalDriven(true);
            work.setTargetItem(itemId);
            agent.setCurrentAction(work);
            villager.getNavigation().stop();
        } else {
            VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE,
                    "Walking to workstation to craft " + itemId);
            move.setTargetBlockPos(jobPos);
            move.setPhase(VillagerAction.ActionPhase.WALKING);
            move.setGoalDriven(true);
            move.setTargetItem(itemId);
            agent.setCurrentAction(move);
            villager.getNavigation().moveTo(jobPos.getX() + 0.5, jobPos.getY(), jobPos.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
        }
        return SkillResult.RUNNING;
    }
}
