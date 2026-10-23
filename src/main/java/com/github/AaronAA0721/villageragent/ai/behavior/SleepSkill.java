package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Root session: resolve a bed, approach it, occupy it, then hold until rested/dawn. */
public class SleepSkill implements VillagerSkill {
    static final long SLEEP_START = 12541, SLEEP_END = 23458;
    public static boolean isNightTime(long time) {
        long t = Math.floorMod(time, 24000L);
        return t >= SLEEP_START && t <= SLEEP_END;
    }
    public static boolean isSleepGoal(AgentGoal goal) {
        return goal != null && ("rest".equals(goal.getGoalType()) || "sleep".equals(goal.getGoalType()));
    }
    private static final class State {
        BlockPos bed;
        boolean nightSession;
        final Set<BlockPos> rejected = new HashSet<>();
    }
    @Override public String id() { return "sleep"; }
    @Override public boolean isComposite() { return true; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return isSleepGoal(g);
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);
        boolean night = isNightTime(w.getDayTime());
        if (night) state.nightSession = true;
        if ((!night && state.nightSession) || (!night && a.getFatigue() <= 10)) {
            if (v.isSleeping()) v.stopSleeping();
            return SkillResult.SUCCESS;
        }
        if (v.isSleeping()) {
            if (state.bed != null && (!w.isLoaded(state.bed)
                    || !(w.getBlockState(state.bed).getBlock() instanceof BedBlock)
                    || v.blockPosition().distSqr(state.bed) > 4)) {
                v.stopSleeping();
                state.rejected.add(state.bed);
                state.bed = null;
            } else {
                a.setFatigue(Math.max(0, a.getFatigue() - 0.05f));
                // Productive rest must not consume the root's no-progress timeout.
                a.brain().goalStartedTick = w.getGameTime();
                return SkillResult.RUNNING;
            }
        }
        if (state.bed == null) {
            state.bed = resolve(a, v, w, state.rejected);
            if (state.bed == null) {
                g.setLastFailure(FailureReason.UNREACHABLE.with("no available reachable bed"));
                return SkillResult.BLOCKED;
            }
        }
        BlockPos bed = state.bed;
        // Keep an existing child until it terminates, even if already close to the bed.
        if (a.brain().tasks.current().child() != null || v.blockPosition().distSqr(bed) > 4) {
            SkillResult result = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Approach bed", g.getPriority());
                move.setTargetLocation(bed);
                move.setArrivalDistanceSq(4);
                return move;
            });
            if (result == SkillResult.RUNNING) return result;
            if (result != SkillResult.SUCCESS) {
                state.rejected.add(bed); state.bed = null;
                return SkillResult.RUNNING; // Try another bed within this root session.
            }
        }
        if (!available(w, bed)) {
            state.rejected.add(bed); state.bed = null;
            return SkillResult.RUNNING;
        }
        v.getNavigation().stop();
        v.startSleeping(bed);
        a.setHomeBedPos(bed);
        a.brain().goalStartedTick = w.getGameTime();
        BrainLog.diagnostic(a, "sleep-start", "bed=" + bed + " night=" + night);
        return SkillResult.RUNNING;
    }
    /** Compatibility entry for the legacy scheduled activity path. */
    public static void tickRest(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        if (a.getRunningGoal() != null) return;
        TaskExecution.Frame frame = a.brain().tasks.root();
        if (frame != null && !isSleepGoal(frame.goal)) return;
        AgentGoal goal = frame == null ? new AgentGoal("rest", "Rest", 8) : frame.goal;
        if (BehaviorExecutor.executeRoot(a, goal, v, w) != SkillResult.RUNNING)
            ActionLifecycle.cancel(a, v, w);
    }
    private static BlockPos resolve(VillagerAgentData a, VillagerEntity v, ServerWorld w, Set<BlockPos> rejected) {
        List<BlockPos> candidates = new ArrayList<>();
        Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
        if (home.isPresent() && home.get().dimension().equals(w.dimension())) candidates.add(home.get().pos());
        if (a.getHomeBedPos() != null) candidates.add(a.getHomeBedPos());
        List<BlockPos> nearby = new ArrayList<>();
        for (BuildingRecord record : WorldStructureIndex.instance(w).queryNear(v.blockPosition(), ModConfig.HOME_SEARCH_RADIUS_CHUNKS.get())) {
            nearby.addAll(record.beds);
            if (record.seedBed != null) nearby.add(record.seedBed);
        }
        nearby.sort(Comparator.comparingDouble(pos -> pos.distSqr(v.blockPosition())));
        candidates.addAll(nearby);
        for (BlockPos bed : candidates) {
            if (!rejected.contains(bed) && available(w, bed)) return bed.immutable();
        }
        // A nearby bed can be usable before building perception has indexed its room.
        BlockPos origin = v.blockPosition(), best = null;
        double distance = Double.MAX_VALUE;
        for (BlockPos bed : BlockPos.betweenClosed(origin.offset(-16, -4, -16), origin.offset(16, 4, 16))) {
            if (rejected.contains(bed) || !available(w, bed)) continue;
            double d = bed.distSqr(origin);
            if (d < distance) { distance = d; best = bed.immutable(); }
        }
        return best;
    }
    private static boolean available(ServerWorld w, BlockPos bed) {
        if (!w.isLoaded(bed)) return false;
        BlockState state = w.getBlockState(bed);
        return state.getBlock() instanceof BedBlock && !state.getValue(BedBlock.OCCUPIED);
    }
}
