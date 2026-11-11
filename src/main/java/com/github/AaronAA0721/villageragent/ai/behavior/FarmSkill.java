package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.*;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** A continuous field session: travel, collect/harvest/plant/till, inspect again, finish. */
public class FarmSkill implements VillagerSkill {
    public static final int PATCH_RADIUS = 12;
    public static final int SESSION_LIMIT = 6000, SESSION_COOLDOWN = 1200;
    private static final int LOCAL_RADIUS = 3;
    /** TILL_NEW: bootstrap tilling beside water while the farmer knows fewer than 16 farmland blocks. */
    enum Step { COLLECT, HARVEST, PLANT, TILL_REVERTED, TILL_RECLAIM, TILL_NEW }
    private static final class Plan {
        final Step step;
        final BlockPos target;
        Plan(Step step, BlockPos target) { this.step = step; this.target = target.immutable(); }
    }
    @Override public String id() { return "farm"; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.FARMING; }
    @Override public int minLevel() { return 1; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "farm".equals(g.getGoalType()) && passesCapabilityGate(a);
    }

    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // More mature crops / remembered farmland = higher motivation
        return Math.min(4, (a.brain().farmOpportunities + (a.brain().rememberedFarm != null ? 3 : 0)) / 3.0);
    }
    private static final class State {
        BlockPos center, field;
        boolean bootstrapTrip;
        long nextStep, refreshAt, started = -1;
        BlockPos focus;
        boolean focusRefreshed;
        List<Plan> pending;
        Failure missing;
        int completed;
        final Set<BlockPos> rejected = new HashSet<>();
        Plan plan;
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        if (state.started < 0) state.started = now;
        g.setCooldownTicks(Math.max(g.getCooldownTicks(), SESSION_COOLDOWN));
        g.setMinimumExitCooldownTicks(SESSION_COOLDOWN);
        if (now - state.started >= SESSION_LIMIT) {
            BrainLog.diagnostic(a, "farm-session-limit", "steps=" + state.completed);
            return SkillResult.SUCCESS;
        }
        if (now < state.nextStep) return SkillResult.RUNNING;
        if (state.field != null) {
            BlockPos anchor = state.field;
            SkillResult moved = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal child = new AgentGoal("move", "Travel to field", g.getPriority());
                child.setTargetLocation(anchor.above());
                return child;
            });
            if (moved == SkillResult.RUNNING) return moved;
            FarmMemory.defer(a, anchor, now, moved.name());
            state.field = null;
            if (moved != SkillResult.SUCCESS) return moved;
            if (!state.bootstrapTrip) FarmMemory.validateArrival(a, anchor, w);
            state.center = anchor;
            state.pending = null; state.focus = null;
            a.brain().goalStartedTick = now;
        }
        if (state.center == null) {
            // Before scanning locally, check if we should travel to remembered farm first
            BlockPos field = a.brain().rememberedFarm;
            if (state.completed == 0 && field != null && field.distSqr(v.blockPosition()) > 64) {
                state.field = field;
                return SkillResult.RUNNING; // walk there first, then scan on arrival
            }
            // No field remembered: go and start one beside the water near the job site.
            BlockPos site = a.brain().bootstrapSite;
            if (state.completed == 0 && field == null && site != null && FarmMemory.bootstrapping(a)
                    && site.distSqr(v.blockPosition()) > 64) {
                state.field = site; state.bootstrapTrip = true;
                BrainLog.diagnostic(a, "farm-bootstrap", "site=" + site + " knownFarmland=" + a.brain().knownFarmland);
                return SkillResult.RUNNING;
            }
            state.center = v.blockPosition().below();
        }
        if (state.plan == null) {
            if (state.pending == null) refresh(a, v, w, state, g);
            BlockPos field = a.brain().rememberedFarm;
            for (int probes = 0; probes < 4; probes++) {
                state.pending.removeIf(p -> state.rejected.contains(p.target) || (p.step != Step.COLLECT && !valid(p.step, p.target, a, w))
                        || (p.step == Step.COLLECT && p.target.distSqr(v.blockPosition()) <= 2));
                Plan selected = choose(state, v.blockPosition());
                if (selected == null && state.focus != null && !state.focusRefreshed) {
                    if (now < state.refreshAt) return SkillResult.RUNNING;
                    refresh(a, v, w, state, g); // Catch empty ground/new seeds before leaving this patch.
                    state.focusRefreshed = true;
                    selected = choose(state, v.blockPosition());
                }
                if (selected == null) {
                    state.focus = null;
                    // Prefer existing crops/empty farmland over opening new land farther away.
                    Plan nearest = state.pending.stream().filter(p -> !state.rejected.contains(p.target))
                            .min(Comparator.comparingInt((Plan p) -> p.step == Step.TILL_RECLAIM || p.step == Step.TILL_NEW ? 1 : 0)
                                    .thenComparingDouble(p -> p.target.distSqr(v.blockPosition()))).orElse(null);
                    if (nearest == null) {
                        if (field != null) FarmMemory.defer(a, field, now, "session exhausted");
                        if (state.missing != null) { g.setLastFailure(state.missing); return SkillResult.BLOCKED; }
                        // Only count as success if we actually did some work
                        return state.completed > 0 ? SkillResult.SUCCESS : SkillResult.WAITING;
                    }
                    state.focus = nearest.target;
                    state.focusRefreshed = false;
                    selected = choose(state, v.blockPosition());
                    BrainLog.diagnostic(a, "farm-patch", "center=" + state.focus);
                }
                if (selected == null) continue;
                state.pending.remove(selected);
                BlockPos stand = selected.step == Step.PLANT || selected.step == Step.TILL_REVERTED
                        || selected.step == Step.TILL_RECLAIM || selected.step == Step.TILL_NEW ? selected.target.above() : selected.target;
                // Nearby actions need no path search; distant candidates are probed at most four per tick.
                boolean near = v.blockPosition().distSqr(stand) <= 2
                        || (selected.step != Step.COLLECT && FarmingAction.canWork(v, selected.target));
                Path path = near ? null : VillagerPathfinding.compute(v, stand, 1);
                if (near || (path != null && path.canReach())) {
                    state.plan = selected; break;
                }
                state.rejected.add(selected.target);
            }
            if (state.plan == null) return SkillResult.RUNNING;
        }
        Plan plan = state.plan;
        SkillResult result = BehaviorExecutor.executeChild(a, v, w, () -> {
            String type = plan.step == Step.COLLECT ? "collect_crops" : plan.step == Step.HARVEST ? "harvest"
                    : plan.step == Step.PLANT ? "plant" : "till";
            AgentGoal child = new AgentGoal(type, "Field " + type, g.getPriority());
            child.setTargetLocation(plan.target);
            return child;
        });
        if (result == SkillResult.RUNNING) return result;
        if (result == SkillResult.SUCCESS) {
            state.completed++;
            if (plan.step == Step.HARVEST || plan.step == Step.COLLECT) {
                state.focusRefreshed = false;
                state.refreshAt = now + 12; // Let passive collection run before the final patch rescan.
            }
            a.setFarmTicks(state.completed);
            a.brain().goalStartedTick = now;
            // Between two tended crops the farmer may choose to do something else.
            GoalCommitment.workUnitDone(a, now);
        } else state.rejected.add(plan.target);
        // One visit per drop: arrival "succeeds" even when contact pickup could not take the item (e.g. it
        // lies diagonally just outside the pickup box), so re-planning it would loop in place forever.
        if (plan.step == Step.COLLECT) state.rejected.add(plan.target);
        // Failure belongs to this target; the parent scans other work before deciding to exit.
        g.setLastFailure(null);
        state.plan = null;
        if (result == SkillResult.SUCCESS) {
            BlockPos soil = plan.step == Step.HARVEST ? plan.target.below() : plan.target;
            if (FarmingAction.hasSeeds(a) && FarmingAction.isEmptyFarmland(w, soil))
                state.pending.add(new Plan(Step.PLANT, soil));
        }
        state.nextStep = now + 2;
        return SkillResult.RUNNING;
    }

    private static Plan choose(State state, BlockPos here) {
        if (state.focus == null) return null;
        return state.pending.stream().filter(p -> !state.rejected.contains(p.target)
                && Math.abs(p.target.getX() - state.focus.getX()) <= LOCAL_RADIUS
                && Math.abs(p.target.getZ() - state.focus.getZ()) <= LOCAL_RADIUS
                && Math.abs(p.target.getY() - state.focus.getY()) <= 2)
                .min(Comparator.comparingInt((Plan p) -> p.step == Step.HARVEST ? 0 : p.step == Step.PLANT ? 1
                        : p.step == Step.COLLECT ? 2 : p.step == Step.TILL_REVERTED ? 3 : p.step == Step.TILL_RECLAIM ? 4 : 5)
                        .thenComparingDouble(p -> p.target.distSqr(here))).orElse(null);
    }
    private static void refresh(VillagerAgentData a, VillagerEntity v, ServerWorld w, State state, AgentGoal goal) {
        goal.setLastFailure(null);
        state.pending = scan(a, v, w, state, goal);
        state.missing = goal.getLastFailure();
    }

    private static List<Plan> scan(VillagerAgentData a, VillagerEntity v, ServerWorld w, State state, AgentGoal goal) {
        List<Plan> plans = new ArrayList<>();
        BlockPos center = state.center;
        boolean seeds = FarmingAction.hasSeeds(a), hoe = FarmingAction.hasHoe(a);
        int level = a.getCapabilityPool().levelOf(CapabilityId.FARMING);
        // Bootstrap: fewer than 16 known farmland blocks -> any tillable ground within water range qualifies,
        // regardless of level or neighbouring farmland. Water is indexed once so the per-block check is cheap.
        int budget = FarmMemory.bootstrapping(a) ? FarmMemory.BOOTSTRAP_FIELD_SIZE - a.brain().knownFarmland : 0;
        List<BlockPos> water = new ArrayList<>();
        if (budget > 0)
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-PATCH_RADIUS - 4, -4, -PATCH_RADIUS - 4),
                    center.offset(PATCH_RADIUS + 4, 4, PATCH_RADIUS + 4)))
                if (w.isLoaded(p) && w.getBlockState(p).getBlock() == net.minecraft.block.Blocks.WATER) {
                    water.add(p.immutable());
                    if (water.size() >= 512) break;
                }
        int newTills = 0;

        // ── Priority pass: damaged farmland from event memory (bypass normal adjacency check) ──
        java.util.Set<BlockPos> damaged = a.brain().damagedFarmland;
        if (hoe && !damaged.isEmpty()) {
            java.util.Iterator<BlockPos> it = damaged.iterator();
            while (it.hasNext()) {
                BlockPos dp = it.next();
                // Only consider positions within the current scan patch
                if (dp.distSqr(center) > (long) PATCH_RADIUS * PATCH_RADIUS * 3) continue;
                if (!w.isLoaded(dp) || state.rejected.contains(dp)) { it.remove(); continue; }
                // If it's already farmland again, remove from memory
                if (FarmingAction.isFarmland(w, dp)) { it.remove(); continue; }
                // Tillable dirt with water nearby → re-till even without neighbor farmland
                if (FarmingAction.isTillableDirt(w, dp) && w.isEmptyBlock(dp.above())
                        && FarmingAction.hasWaterNearby(w, dp)) {
                    plans.add(new Plan(Step.TILL_REVERTED, dp));
                    it.remove(); // consumed — will be re-added if it fails
                }
            }
        }

        for (ItemEntity drop : drops(w, center, PATCH_RADIUS)) {
            if (drop.blockPosition().distSqr(v.blockPosition()) > 2)
                plans.add(new Plan(Step.COLLECT, drop.blockPosition()));
        }
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-PATCH_RADIUS, -3, -PATCH_RADIUS),
                center.offset(PATCH_RADIUS, 3, PATCH_RADIUS))) {
            if (!w.isLoaded(p) || state.rejected.contains(p)) continue;
            if (FarmingAction.isMatureCrop(w, p)) plans.add(new Plan(Step.HARVEST, p));
            else if (FarmingAction.isEmptyFarmland(w, p)) {
                if (seeds) plans.add(new Plan(Step.PLANT, p));
                else if (goal != null) goal.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:wheat_seeds"));
            } else if (w.isEmptyBlock(p.above()) && FarmingAction.isRevertedFarmland(w, p)) {
                if (hoe) plans.add(new Plan(Step.TILL_REVERTED, p));
                else if (goal != null) goal.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:iron_hoe"));
            } else if (level >= 2 && w.isEmptyBlock(p.above()) && FarmingAction.isCultivatableDirt(w, p)) {
                if (hoe) plans.add(new Plan(Step.TILL_RECLAIM, p));
                else if (goal != null) goal.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:iron_hoe"));
            } else if (newTills < budget && !water.isEmpty() && w.isEmptyBlock(p.above())
                    && FarmingAction.isTillableDirt(w, p) && nearWater(water, p)) {
                if (hoe) { plans.add(new Plan(Step.TILL_NEW, p)); newTills++; }
                else if (goal != null) goal.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:iron_hoe"));
            }
        }
        return plans;
    }
    /** Same range as vanilla hydration ({@link FarmingAction#hasWaterNearby}): 4 blocks, same level or one below. */
    private static boolean nearWater(List<BlockPos> water, BlockPos p) {
        for (BlockPos q : water) {
            int dy = q.getY() - p.getY();
            if (Math.abs(q.getX() - p.getX()) <= FarmingAction.WATER_RADIUS && Math.abs(q.getZ() - p.getZ()) <= FarmingAction.WATER_RADIUS
                    && dy >= -1 && dy <= 0) return true;
        }
        return false;
    }
    /** Bootstrap tilling applies to this block for this farmer. */
    static boolean newFieldTill(VillagerAgentData a, ServerWorld w, BlockPos p) {
        return FarmMemory.bootstrapping(a) && w.isEmptyBlock(p.above()) && FarmingAction.isTillableDirt(w, p)
                && FarmingAction.hasWaterNearby(w, p);
    }
    static boolean valid(Step step, BlockPos p, VillagerAgentData a, ServerWorld w) {
        if (!w.isLoaded(p)) return false;
        switch (step) {
            case COLLECT: return !drops(w, p, 1.5).isEmpty();
            case HARVEST: return FarmingAction.isMatureCrop(w, p);
            case PLANT: return FarmingAction.hasSeeds(a) && FarmingAction.isEmptyFarmland(w, p);
            case TILL_REVERTED: return FarmingAction.hasHoe(a) && w.isEmptyBlock(p.above()) && FarmingAction.isRevertedFarmland(w, p);
            case TILL_RECLAIM: return FarmingAction.hasHoe(a) && w.isEmptyBlock(p.above()) && FarmingAction.isCultivatableDirt(w, p);
            case TILL_NEW: return FarmingAction.hasHoe(a) && newFieldTill(a, w, p);
            default: return false;
        }
    }
    static boolean perform(Step step, BlockPos p, VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        switch (step) {
            case COLLECT: return true; // Arrival completes the step; passive attraction collects items.
            case HARVEST:
                net.minecraft.block.Block crop = FarmingAction.harvestBlockAt(v, w, a, p);
                if (crop == null) return false;
                // Replant immediately from existing stock, preferring the crop just harvested.
                if (!FarmingAction.plantSpecificCropAt(v, w, a, p.below(), crop))
                    FarmingAction.plantSmartAt(v, w, a, p.below());
                return true;
            case PLANT: return FarmingAction.plantSmartAt(v, w, a, p);
            case TILL_REVERTED: case TILL_RECLAIM: return FarmingAction.tillBlockAt(v, w, a, p);
            case TILL_NEW:
                if (!FarmingAction.tillBlockAt(v, w, a, p, false)) return false;
                a.brain().knownFarmland++; // counted now; the periodic recount confirms it
                return true;
            default: return false;
        }
    }
    private static List<ItemEntity> drops(ServerWorld w, BlockPos p, double radius) {
        return w.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(p).inflate(radius),
                e -> e.isAlive() && !com.github.AaronAA0721.villageragent.ai.CraftDisplay.isDisplay(e) && cropItem(e.getItem().getItem()));
    }
    private static boolean cropItem(Item item) {
        return item == Items.WHEAT || item == Items.WHEAT_SEEDS || item == Items.CARROT
                || item == Items.POTATO || item == Items.POISONOUS_POTATO || item == Items.BEETROOT
                || item == Items.BEETROOT_SEEDS || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS;
    }
}
