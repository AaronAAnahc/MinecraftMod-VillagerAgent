package com.github.AaronAA0721.villageragent.ai;

import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Transient execution/perception state; all timestamps are server game ticks. */
public final class BrainRuntime {
    /** Vanilla fluid jump task survives goal switches; the intercepted Brain no longer ticks it. */
    public final net.minecraft.entity.ai.brain.task.SwimTask swim = new net.minecraft.entity.ai.brain.task.SwimTask(0.8F);
    public long lastSwimDiagnosticTick = Long.MIN_VALUE;
    public long lastMovementDiagnosticTick = Long.MIN_VALUE;
    public net.minecraft.util.math.vector.Vector3d lastSwimDiagnosticPosition;
    public boolean nightRest;
    /** Last direct sighting; alarm responses never refresh personal threat memory. */
    public long lastThreatSeenTick = -1;
    public long socialUntilTick;
    public long lastNeedsTick = -1;
    public long lastFatigueTick = -1;
    public VillagerAction navigationAction;
    public net.minecraft.util.math.vector.Vector3d navigationProgress;
    public long navigationProgressTick, nextNavigationRecovery;
    public int navigationRecoveries;
    public long lastChoiceTick = -100;
    /** Rate limit diagnostic snapshots per villager; never affects scheduling. */
    public long lastDiagnosticTick = Long.MIN_VALUE;
    public long lastNoCandidateTick = Long.MIN_VALUE;
    public long lastOfferLogTick = Long.MIN_VALUE;
    /** A wooden door opened for this villager's active navigation path. */
    public final Set<BlockPos> openedDoors = new HashSet<>();
    public final java.util.Map<String, Long> retiredRoutines = new java.util.HashMap<>();
    public VillagerAction observedWalk;
    public long walkStartedTick;
    public long goalStartedTick;
    /** Tick the running goal was selected; skills never touch it (unlike {@link #goalStartedTick}). */
    public long goalSelectedTick;
    /** Work units (tree felled, block mined, crop tended) finished by the running goal. See {@link GoalCommitment}. */
    public int workUnitsDone;
    /** Until this tick the running goal sits between two work units and may be swapped out. */
    public long yieldWindowUntilTick = Long.MIN_VALUE;
    /** This villager's posted cooperative requests and the tasks waiting on them. See {@link OwnRequests}. */
    public final java.util.Map<java.util.UUID, OwnRequests.Need> ownRequests = new java.util.HashMap<>();
    public long ownRequestsCheckedTick;
    public long executionWatchdogTick;
    public final TaskExecution tasks = new TaskExecution();
    public final AcquisitionTasks acquisitions = new AcquisitionTasks();
    /** Snapshot of the village board from this villager's last physical read. */
    public final BoardMemory boardMemory = new BoardMemory();
    public int farmOpportunities;
    public BlockPos rememberedFarm;
    public final java.util.Map<BlockPos, Long> farmRevisitAfter = new java.util.HashMap<>();
    public final java.util.Map<BlockPos, Long> rejectedJobSites = new java.util.HashMap<>();
    public long lastFarmMemoryScan = -200;
    /** Distinct farmland blocks around remembered fields (FarmMemory, refreshed every 200 ticks). */
    public int knownFarmland;
    /** Bootstrap mode only: tillable ground beside water near the job site where a first field can start. */
    public BlockPos bootstrapSite;
    public long lastBootstrapScan = -1200;

    /** Farmland positions destroyed by external events (raids, player); farmers prioritize re-tilling these. */
    public final Set<BlockPos> damagedFarmland = new HashSet<>();

    public UUID socialPartner;

    // ── Per-type recency tracking (replaces single lastFinishedType/Tick) ──
    /** Game tick when each goal type last completed successfully. */
    public final java.util.Map<String, Long> lastFinishedTicks = new java.util.HashMap<>();
    /** Duration (ticks) of the last successful session for each goal type. */
    public final java.util.Map<String, Integer> lastSessionDurations = new java.util.HashMap<>();


    // ── Tether: constrains wander/movement while a villager is waiting near a workstation ──
    /** Non-null while the villager should stay near a point (e.g. furnace). Cleared when done. */
    public BlockPos tetherPos;
    /** Game tick at which the tethered task is ready for collection (e.g. smelt finishes). */
    public long tetherReadyTick;
    /** Horizontal radius (blocks) the villager may wander while tethered. */
    public int tetherRadius = 16;
    /** Clear the tether. */
    public void clearTether() { tetherPos = null; tetherReadyTick = 0; tetherRadius = 16; }
    /** Whether the villager currently has an active tether. */
    public boolean isTethered() { return tetherPos != null; }
    /** Whether the given position is within tether range. */
    public boolean withinTether(BlockPos pos) {
        if (tetherPos == null || pos == null) return true;
        double dx = pos.getX() - tetherPos.getX(), dz = pos.getZ() - tetherPos.getZ();
        return dx * dx + dz * dz <= (double) tetherRadius * tetherRadius;
    }
}
