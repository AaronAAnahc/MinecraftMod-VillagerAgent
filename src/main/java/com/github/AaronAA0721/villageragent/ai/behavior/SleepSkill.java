package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.config.ModConfig;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * The "go to bed and sleep" skill. This is the canonical example of a task that does NOT
 * need the LLM: navigation to a bed and sleeping are pure reflex/programmatic behaviour.
 *
 * <p>It closes the long-standing HOME-refill gap (P0): a villager that has no vanilla
 * {@code MemoryModuleType.HOME} (the mod suppresses the vanilla AI) still needs a bed to
 * sleep at. {@link BedResolver} finds one — first from a persisted home bed, then from the
 * nearest building reported by {@link WorldStructureIndex} — and remembers it for next time.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #canHandle}/{@link #execute} — goal-driven, invoked by {@link BehaviorExecutor}
 *       for a {@code rest}/{@code sleep} {@link AgentGoal}.</li>
 *   <li>{@link #tickRest} — the scheduled-activity path: {@code VillagerActivitySystem} routes
 *       its {@code "resting"} daily-schedule slot here, so nightly sleep works even with no goal.</li>
 * </ul>
 */
public class SleepSkill implements VillagerSkill {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Within 4 blocks counts as arrived at the bed. */
    private static final double ARRIVE_SQ = 16.0;
    /** Ticks before giving up on a blocked path (~10 s at 20 tps). */
    private static final int STUCK_TIMEOUT = 200;
    /** Vanilla sleep window (dayTime modulo 24000). */
    private static final long SLEEP_START = 12541;
    private static final long SLEEP_END   = 23458;

    @Override
    public String id() { return "sleep"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String t = goal.getGoalType();
        return "rest".equals(t) || "sleep".equals(t);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        int r = tickRestInternal(agent, villager, world, true);
        switch (r) {
            case 0:  return SkillResult.RUNNING;
            case 1:  return SkillResult.SUCCESS;
            case 2:  return SkillResult.BLOCKED;
            default: return SkillResult.FAIL;
        }
    }

    /** Scheduled-activity entry: route a {@code "resting"} day-slot through here. */
    public static void tickRest(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        tickRestInternal(agent, villager, world, false);
    }

    /**
     * @return 0 = still walking, 1 = arrived/asleep (done), 2 = no bed found (blocked),
     *         3 = unreachable (fail).
     */
    private static int tickRestInternal(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, boolean fromGoal) {
        if (villager.isSleeping()) {
            if (fromGoal) agent.setCurrentAction(null);
            return 1;
        }

        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isRestMove()) {
            BlockPos target = cur.getTargetBlockPos();
            if (target == null) { agent.setCurrentAction(null); return 3; }
            double distSq = villager.blockPosition().distSqr(target);
            if (distSq <= ARRIVE_SQ) {
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                trySleep(villager, world, target);
                return 1;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach my bed — the path was blocked");
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                return 2;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.3);
            }
            return 0;
        }

        if (cur != null) {
            // Some unrelated action owns the villager. A scheduled rest yields to it; a
            // goal-driven rest waits its turn (don't clear someone else's action).
            return fromGoal ? 0 : 1;
        }

        // No current action: resolve a bed and either sleep (if already there) or walk to it.
        BlockPos bed = BedResolver.resolve(agent, villager, world);
        if (bed == null) {
            agent.addMemory("Wanted to rest but couldn't find a bed nearby");
            return 2;
        }
        double distSq = villager.blockPosition().distSqr(bed);
        if (distSq <= ARRIVE_SQ) {
            trySleep(villager, world, bed);
            return 1;
        }
        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Going to bed to rest");
        move.setTargetBlockPos(bed);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        move.setRestMove(true);
        move.setGoalDriven(fromGoal);
        agent.setCurrentAction(move);
        villager.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 0.3);
        return 0;
    }

    private static void trySleep(VillagerEntity villager, ServerWorld world, BlockPos bed) {
        long t = world.getDayTime() % 24_000L;
        boolean night = t >= SLEEP_START && t <= SLEEP_END;
        if (night && !villager.isSleeping()) {
            villager.startSleeping(bed);
        } else {
            villager.getNavigation().stop();
        }
    }

    /**
     * Resolves the bed a villager should sleep in, in priority order:
     * <ol>
     *   <li>vanilla {@code MemoryModuleType.HOME} (if anything ever populates it);</li>
     *   <li>this villager's persisted home bed (remembered from a previous rest);</li>
     *   <li>nearest building with a seed bed from {@link WorldStructureIndex} — this is the
     *       P0 closure, since the suppressed vanilla AI never assigns a HOME.</li>
     * </ol>
     * A persisted bed whose block is no longer a bed is cleared so it gets re-resolved
     * (handles a demolished house).
     */
    private static final class BedResolver {
        static BlockPos resolve(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
            Optional<GlobalPos> homeOpt = villager.getBrain().getMemory(MemoryModuleType.HOME);
            if (homeOpt.isPresent() && isBed(world, homeOpt.get().pos())) {
                return homeOpt.get().pos();
            }

            BlockPos saved = agent.getHomeBedPos();
            if (saved != null) {
                if (isBed(world, saved)) return saved;
                agent.setHomeBedPos(null); // bed/structure gone — re-resolve below
            }

            int r = ModConfig.HOME_SEARCH_RADIUS_CHUNKS.get();
            List<BuildingRecord> nearby = WorldStructureIndex.instance(world).queryNear(villager.blockPosition(), r);
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BuildingRecord rec : nearby) {
                if (rec.seedBed == null || !isBed(world, rec.seedBed)) continue;
                double d = villager.blockPosition().distSqr(rec.seedBed);
                if (d < bestD) { bestD = d; best = rec.seedBed; }
            }
            if (best != null) {
                agent.setHomeBedPos(best.immutable()); // "this is my house" — persisted
                return best;
            }
            return null;
        }

        private static boolean isBed(ServerWorld world, BlockPos p) {
            return world.getBlockState(p).getBlock() instanceof BedBlock;
        }
    }
}
