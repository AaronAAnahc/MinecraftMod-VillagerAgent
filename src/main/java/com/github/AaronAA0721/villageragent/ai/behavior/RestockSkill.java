package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;

/**
 * Daily workstation restock: walk to job site, receive raw-material supplies.
 * Tools are NOT granted — only consumable materials defined in DailySupplies.
 * Runs once per MC day (24 000 tick cooldown, seeded by RoutineScheduler).
 */
public final class RestockSkill implements VillagerSkill {

    private static final class State {
        BlockPos target;
        BlockPos stand;
    }

    @Override public String id() { return "restock"; }
    @Override public boolean isComposite() { return true; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // Only restock when actually missing items
        for (String item : g.getOutcomeItems())
            if (com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(a, item) == 0) return 1.0;
        return 0; // have everything, skip
    }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        if (!"restock".equals(g.getGoalType())) return false;
        // Only professions with actual daily supply definitions.
        return DailySupplies.has(a.getProfession());
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);

        GlobalPos gp = v.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (gp == null || !gp.dimension().equals(w.dimension()) || !w.isLoaded(gp.pos())
                || !net.minecraft.village.PointOfInterestType.forState(w.getBlockState(gp.pos()))
                    .map(t -> v.getVillagerData().getProfession().getJobPoiType().getPredicate().test(t)).orElse(false)) {
            g.setLastFailure(FailureReason.NO_JOB_SITE.with((String) null));
            return SkillResult.FAIL;
        }
        if (state.target != null && !state.target.equals(gp.pos())) return SkillResult.FAIL;
        state.target = gp.pos();
        if (!atWorkstation(v, w, state.target)) {
            if (state.stand == null) state.stand = ReachablePaths.standingBeside(w, v, state.target);
            if (state.stand == null) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(state.target.toString()));
                return SkillResult.FAIL;
            }
            SkillResult move = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal child = new AgentGoal("move", "Walk to restock workstation", g.getPriority());
                // Any cell touching the stand is close enough to work the bench; an exact cell made
                // the walk fail one block short whenever the stand itself was crowded or awkward.
                child.setTargetLocation(state.stand); child.setArrivalDistanceSq(2.0);
                return child;
            });
            if (move != SkillResult.SUCCESS) return move;
            if (!atWorkstation(v, w, state.target)) return SkillResult.FAIL;
        }
        // Arrival, actual workstation and visibility are checked in the same tick as the grant.
        v.getNavigation().stop();
        int inserted = DailySupplies.grant(a);
        if (inserted == 0) return SkillResult.WAITING;
        a.setLastRestockTime(w.getGameTime());
        a.addMemory("Restocked daily supplies at workstation");
        v.swing(net.minecraft.util.Hand.MAIN_HAND);
        w.playSound(null, state.target, net.minecraft.util.SoundEvents.VILLAGER_WORK_ARMORER,
                net.minecraft.util.SoundCategory.NEUTRAL, 0.8f, 1.0f);
        g.markProgress();
        BrainLog.diagnostic(a, "restock-grant", "profession=" + a.getProfession()
                + " at=" + state.target + " inserted=" + inserted);
        return SkillResult.SUCCESS;
    }

    private static boolean atWorkstation(VillagerEntity v, ServerWorld w, BlockPos target) {
        double d = v.blockPosition().distSqr(target);
        if (d <= 2) return true; // touching the bench (incl. diagonal)
        return d <= 6.25
                && w.clip(new net.minecraft.util.math.RayTraceContext(v.getEyePosition(1),
                    net.minecraft.util.math.vector.Vector3d.atCenterOf(target),
                    net.minecraft.util.math.RayTraceContext.BlockMode.COLLIDER,
                    net.minecraft.util.math.RayTraceContext.FluidMode.NONE, v)).getBlockPos().equals(target);
    }

    /** Items this skill may produce — used by GoalScheduler for outcome awareness. */
    public static java.util.Set<String> outcomeFor(String profession) {
        java.util.Set<String> items = new java.util.HashSet<>();
        for (DailySupplies.Supply s : DailySupplies.get(profession)) {
            if (s.item.getRegistryName() != null) items.add(s.item.getRegistryName().toString());
        }
        return items;
    }
}
