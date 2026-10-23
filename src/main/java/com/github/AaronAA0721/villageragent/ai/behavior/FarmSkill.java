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
    enum Step { COLLECT, HARVEST, PLANT, TILL_REVERTED, TILL_RECLAIM }
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
    private static final class State {
        BlockPos center, field;
        long nextStep;
        int completed;
        final Set<BlockPos> rejected = new HashSet<>();
        Plan plan;
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
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
            FarmMemory.validateArrival(a, anchor, w);
            state.center = anchor;
            a.brain().goalStartedTick = now;
        }
        if (state.center == null) state.center = v.blockPosition().below();
        if (state.plan == null) {
            List<Plan> plans = scan(a, v, w, state, g);
            BlockPos field = a.brain().rememberedFarm;
            if (state.completed == 0 && field != null && field.distSqr(v.blockPosition()) > 64
                    && (plans.isEmpty() || plans.get(0).step == Step.TILL_RECLAIM)) {
                state.field = field;
                return SkillResult.RUNNING;
            }
            int probes = 0;
            for (Plan plan : plans) {
                if (state.rejected.contains(plan.target)) continue;
                if (++probes > 8) { state.nextStep = now + 20; return SkillResult.RUNNING; }
                BlockPos stand = plan.step == Step.PLANT || plan.step == Step.TILL_REVERTED
                        || plan.step == Step.TILL_RECLAIM ? plan.target.above() : plan.target;
                Path path = v.getNavigation().createPath(stand, 1);
                if (v.blockPosition().distSqr(plan.target) <= 2 || (path != null && path.canReach())) {
                    state.plan = plan; break;
                }
                state.rejected.add(plan.target);
            }
            if (state.plan == null) {
                if (field != null) FarmMemory.defer(a, field, now, "session exhausted");
                BrainLog.diagnostic(a, "farm-session-end", "steps=" + state.completed
                        + " rejected=" + state.rejected.size() + " missing=" + g.getLastFailure());
                return g.getLastFailure() == null ? SkillResult.SUCCESS : SkillResult.BLOCKED;
            }
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
            a.setFarmTicks(state.completed);
            a.brain().goalStartedTick = now;
        } else state.rejected.add(plan.target);
        // Failure belongs to this target; the parent scans other work before deciding to exit.
        g.setLastFailure(null);
        state.plan = null;
        state.nextStep = now + 20;
        return SkillResult.RUNNING;
    }

    private static List<Plan> scan(VillagerAgentData a, VillagerEntity v, ServerWorld w, State state, AgentGoal goal) {
        List<Plan> plans = new ArrayList<>();
        BlockPos center = state.center;
        boolean seeds = FarmingAction.hasSeeds(a), hoe = FarmingAction.hasHoe(a);
        int level = a.getCapabilityPool().levelOf(CapabilityId.FARMING);
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
            }
        }
        plans.sort(Comparator.comparingInt((Plan p) -> p.step.ordinal())
                .thenComparingDouble(p -> p.target.distSqr(v.blockPosition())));
        return plans;
    }
    static boolean valid(Step step, BlockPos p, VillagerAgentData a, ServerWorld w) {
        if (!w.isLoaded(p)) return false;
        switch (step) {
            case COLLECT: return !drops(w, p, 1.5).isEmpty();
            case HARVEST: return FarmingAction.isMatureCrop(w, p);
            case PLANT: return FarmingAction.hasSeeds(a) && FarmingAction.isEmptyFarmland(w, p);
            case TILL_REVERTED: return FarmingAction.hasHoe(a) && w.isEmptyBlock(p.above()) && FarmingAction.isRevertedFarmland(w, p);
            case TILL_RECLAIM: return FarmingAction.hasHoe(a) && w.isEmptyBlock(p.above()) && FarmingAction.isCultivatableDirt(w, p);
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
            default: return false;
        }
    }
    private static List<ItemEntity> drops(ServerWorld w, BlockPos p, double radius) {
        return w.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(p).inflate(radius),
                e -> e.isAlive() && cropItem(e.getItem().getItem()));
    }
    private static boolean cropItem(Item item) {
        return item == Items.WHEAT || item == Items.WHEAT_SEEDS || item == Items.CARROT
                || item == Items.POTATO || item == Items.POISONOUS_POTATO || item == Items.BEETROOT
                || item == Items.BEETROOT_SEEDS || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS;
    }
}
