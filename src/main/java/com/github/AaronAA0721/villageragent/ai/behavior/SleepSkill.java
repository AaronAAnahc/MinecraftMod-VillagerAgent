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
    /** Night curfew in force for this world time: everyone sleeps, independent of fatigue. */
    public static boolean curfew(long dayTime) {
        return ModConfig.NIGHT_CURFEW.get() && isNightTime(dayTime);
    }
    public static boolean isSleepGoal(AgentGoal goal) {
        return goal != null && ("rest".equals(goal.getGoalType()) || "sleep".equals(goal.getGoalType()));
    }
    private static final class State {
        BlockPos bed;
        final Set<BlockPos> rejected = new HashSet<>();
        long retryAt;
        /** Outdoor waiting spot while every bed is taken; walked to once per wait. */
        BlockPos waitSpot;
        boolean waiting, waitArrived;
    }

    /** Bed head -> villager heading for it. Runtime only; a claim lapses if not renewed. */
    private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
    private static final Map<BlockPos, Long> CLAIM_UNTIL = new HashMap<>();
    private static final long CLAIM_TICKS = 200;

    private static void claim(BlockPos bed, VillagerEntity v, long now) {
        CLAIMS.put(bed, v.getUUID()); CLAIM_UNTIL.put(bed, now + CLAIM_TICKS);
    }
    private static void release(BlockPos bed, VillagerEntity v) {
        if (bed != null && v.getUUID().equals(CLAIMS.get(bed))) { CLAIMS.remove(bed); CLAIM_UNTIL.remove(bed); }
    }
    /** Another villager is already walking to this bed. */
    private static boolean claimedByOther(BlockPos bed, VillagerEntity v, long now) {
        UUID owner = CLAIMS.get(bed);
        if (owner == null || owner.equals(v.getUUID())) return false;
        if (CLAIM_UNTIL.getOrDefault(bed, 0L) < now) { CLAIMS.remove(bed); CLAIM_UNTIL.remove(bed); return false; }
        return true;
    }
    @Override public String id() { return "sleep"; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // Curfew: bed time dominates every ordinary task at night (GoalScheduler also forces it).
        if (a.brain().nightRest && ModConfig.NIGHT_CURFEW.get()) return 1000;
        if (a.getFatigue() < GoalScheduler.REST_MIN_FATIGUE) return 0;
        double nightBoost = a.brain().nightRest ? 4 : a.getFatigue() >= GoalScheduler.REST_INTERRUPT_FATIGUE ? 1 : 0.005;
        double fatigueScale = Math.max(1, Math.pow(a.getFatigue() / 35.0, 2));
        return nightBoost * fatigueScale;
    }
    @Override public boolean isComposite() { return true; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return isSleepGoal(g);
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State state = a.brain().tasks.current().state(State.class, State::new);
        boolean night = isNightTime(w.getDayTime());
        boolean curfew = curfew(w.getDayTime());
        // Curfew sleepers get up at dawn; a curfew walk to bed that runs into dawn ends unless tired.
        long clock = Math.floorMod(w.getDayTime(), 24000L);
        boolean dawn = clock > SLEEP_END || clock < 1000;
        if (ModConfig.NIGHT_CURFEW.get() && dawn && a.getFatigue() < GoalScheduler.REST_MIN_FATIGUE) {
            if (v.isSleeping()) v.stopSleeping();
            return SkillResult.SUCCESS;
        }
        if (a.getFatigue() <= 0 && !curfew) {
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
                if (a.getFatigue() <= 0 && !curfew) { v.stopSleeping(); return SkillResult.SUCCESS; }
                return SkillResult.RUNNING;
            }
        }
        long now = w.getGameTime();
        if (state.bed == null) {
            if (now < state.retryAt) return waitOutside(a, g, v, w, state);
            state.bed = resolve(a, v, w, state.rejected);
            if (state.bed == null) {
                if (curfew) {
                    // Every bed is taken right now (e.g. someone is still away fighting). Keep the bedtime goal,
                    // wait outdoors at the village board rather than inside someone's room, and look again shortly.
                    state.rejected.clear();
                    state.retryAt = now + 100;
                    if (!state.waiting) {
                        state.waiting = true; state.waitArrived = false; state.waitSpot = waitingSpot(v, w);
                        BrainLog.diagnostic(a, "sleep-no-bed", "wait=" + state.waitSpot);
                    }
                    return waitOutside(a, g, v, w, state);
                }
                g.setLastFailure(FailureReason.UNREACHABLE.with("no available reachable bed"));
                return SkillResult.BLOCKED;
            }
            // A bed turned up: drop any walk to the waiting spot and claim the bed for this villager.
            if (state.waiting) {
                state.waiting = false;
                if (a.brain().tasks.current().child() != null) {
                    ActionLifecycle.clearBody(a, v, w);
                    ActionLifecycle.releaseTree(a.brain().tasks.current().child(), a, w);
                    a.brain().tasks.discardChild();
                }
            }
            claim(state.bed, v, now);
        }
        BlockPos bed = state.bed;
        claim(bed, v, now);
        // Someone else lay down in it (or is heading there first) while we walked: choose again at once instead
        // of walking into the room. Our own bed is taken back on arrival instead.
        if (!bed.equals(ownBed(a, v, w)) && (!available(w, bed) || claimedByOther(bed, v, now))) {
            if (a.brain().tasks.current().child() != null) {
                ActionLifecycle.clearBody(a, v, w);
                ActionLifecycle.releaseTree(a.brain().tasks.current().child(), a, w);
                a.brain().tasks.discardChild();
            }
            release(bed, v);
            state.rejected.add(bed); state.bed = null;
            return SkillResult.RUNNING;
        }
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
                release(bed, v);
                state.rejected.add(bed); state.bed = null;
                return SkillResult.RUNNING; // Try another bed within this root session.
            }
        }
        // The owner takes back their own bed from anyone who borrowed it while they were away.
        if (!available(w, bed) && bed.equals(ownBed(a, v, w))) evictIntruder(w, v, bed);
        if (!available(w, bed)) {
            release(bed, v);
            state.rejected.add(bed); state.bed = null;
            return SkillResult.RUNNING;
        }
        v.getNavigation().stop();
        v.startSleeping(bed);
        release(bed, v);
        if (!hasHomeMemory(v, w)) a.setHomeBedPos(bed);
        a.brain().goalStartedTick = w.getGameTime();
        BrainLog.diagnostic(a, "sleep-start", "bed=" + bed + " night=" + night);
        return SkillResult.RUNNING;
    }
    /** Walk once to the outdoor waiting spot, then stand there until the next bed search. */
    private static SkillResult waitOutside(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State state) {
        a.brain().goalStartedTick = w.getGameTime();
        BlockPos spot = state.waitSpot;
        if (state.waiting && spot != null && !state.waitArrived && v.blockPosition().distSqr(spot) > 4) {
            SkillResult moved = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Wait outside for a free bed", g.getPriority());
                move.setTargetLocation(spot);
                move.setArrivalDistanceSq(4);
                return move;
            });
            if (moved == SkillResult.RUNNING) return SkillResult.RUNNING;
            state.waitArrived = true; // arrived or unreachable: either way stop walking
        }
        v.getNavigation().stop();
        return SkillResult.RUNNING;
    }

    /** Outdoor spot to wait for a bed: beside the village notice board, else the village centre's surface. */
    private static BlockPos waitingSpot(VillagerEntity v, ServerWorld w) {
        BlockPos board = VillageNoticeBoards.position(w, v);
        if (board != null) {
            BlockPos stand = ReachablePaths.standingBeside(w, v, board);
            if (stand != null) return stand;
        }
        VillageMarkets.Market market = VillageMarkets.market(w, v.getUUID());
        if (market == null || market.center == null || !w.isLoaded(market.center)) return null;
        return w.getHeightmapPos(net.minecraft.world.gen.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, market.center);
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
    /**
     * Own bed first (vanilla HOME, else the last bed this villager slept in when it has no HOME). Other beds are
     * only borrowed when no other villager owns them. Beds are compared by their HEAD block: sleeping on the foot
     * half does not mark the head occupied, which let two villagers share one bed.
     */
    private static BlockPos resolve(VillagerAgentData a, VillagerEntity v, ServerWorld w, Set<BlockPos> rejected) {
        BlockPos own = ownBed(a, v, w);
        if (own != null && !rejected.contains(own) && (available(w, own) || occupiedByOther(w, own, v))) return own;
        Set<BlockPos> owned = ownedByOthers(v, w);
        List<BlockPos> nearby = new ArrayList<>();
        for (BuildingRecord record : WorldStructureIndex.instance(w).queryNear(v.blockPosition(), ModConfig.HOME_SEARCH_RADIUS_CHUNKS.get())) {
            nearby.addAll(record.beds);
            if (record.seedBed != null) nearby.add(record.seedBed);
        }
        nearby.sort(Comparator.comparingDouble(pos -> pos.distSqr(v.blockPosition())));
        for (BlockPos bed : nearby) {
            if (!w.isLoaded(bed)) continue;
            BlockPos head = head(w, bed);
            if (!rejected.contains(head) && !owned.contains(head) && available(w, head)
                    && !claimedByOther(head, v, w.getGameTime())) return head;
        }
        // A nearby bed can be usable before building perception has indexed its room.
        BlockPos origin = v.blockPosition(), best = null;
        double distance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -4, -16), origin.offset(16, 4, 16))) {
            if (!(w.getBlockState(pos).getBlock() instanceof BedBlock)) continue;
            BlockPos head = head(w, pos);
            if (rejected.contains(head) || owned.contains(head) || !available(w, head)
                    || claimedByOther(head, v, w.getGameTime())) continue;
            double d = head.distSqr(origin);
            if (d < distance) { distance = d; best = head; }
        }
        return best;
    }
    /** Head half of a bed (the half vanilla sleeps on and marks occupied). */
    static BlockPos head(ServerWorld w, BlockPos pos) {
        BlockState state = w.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == net.minecraft.state.properties.BedPart.FOOT)
            return pos.relative(state.getValue(BedBlock.FACING)).immutable();
        return pos.immutable();
    }
    private static boolean hasHomeMemory(VillagerEntity v, ServerWorld w) {
        try {
            Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
            return home.isPresent() && home.get().dimension().equals(w.dimension());
        } catch (RuntimeException e) { return false; }
    }
    /** This villager's own bed (head position), or null. */
    static BlockPos ownBed(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        BlockPos pos = null;
        try {
            Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
            if (home.isPresent() && home.get().dimension().equals(w.dimension())) pos = home.get().pos();
        } catch (RuntimeException ignored) {}
        if (pos == null) pos = a.getHomeBedPos();
        if (pos == null || !w.isLoaded(pos) || !(w.getBlockState(pos).getBlock() instanceof BedBlock)) return null;
        return head(w, pos);
    }
    /** Beds that belong to some other loaded, managed villager. */
    private static Set<BlockPos> ownedByOthers(VillagerEntity self, ServerWorld w) {
        Set<BlockPos> owned = new HashSet<>();
        for (VillagerAgentData other : VillagerAgentManager.getAllAgents()) {
            if (other == null || self.getUUID().equals(other.getVillagerId())) continue;
            net.minecraft.entity.Entity e = w.getEntity(other.getVillagerId());
            if (!(e instanceof VillagerEntity) || !e.isAlive()) continue;
            BlockPos bed = ownBed(other, (VillagerEntity) e, w);
            if (bed != null) owned.add(bed);
        }
        return owned;
    }
    private static boolean occupiedByOther(ServerWorld w, BlockPos head, VillagerEntity self) {
        for (VillagerEntity other : w.getEntitiesOfClass(VillagerEntity.class, new net.minecraft.util.math.AxisAlignedBB(head).inflate(2)))
            if (other != self && other.isSleeping() && other.getSleepingPos().map(p -> head(w, p).equals(head)).orElse(false)) return true;
        return false;
    }
    private static void evictIntruder(ServerWorld w, VillagerEntity owner, BlockPos head) {
        for (VillagerEntity other : w.getEntitiesOfClass(VillagerEntity.class, new net.minecraft.util.math.AxisAlignedBB(head).inflate(2)))
            if (other != owner && other.isSleeping() && other.getSleepingPos().map(p -> head(w, p).equals(head)).orElse(false)) {
                other.stopSleeping();
                VillagerAgentData agent = VillagerAgentManager.getAgent(other.getUUID());
                if (agent != null) BrainLog.diagnostic(agent, "bed-evicted", "bed=" + head + " owner=" + owner.getName().getString());
            }
    }
    private static boolean available(ServerWorld w, BlockPos bed) {
        if (!w.isLoaded(bed)) return false;
        BlockState state = w.getBlockState(bed);
        return state.getBlock() instanceof BedBlock && !state.getValue(BedBlock.OCCUPIED);
    }
}
