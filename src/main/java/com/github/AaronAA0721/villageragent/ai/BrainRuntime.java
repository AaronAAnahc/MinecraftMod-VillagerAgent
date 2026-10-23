package com.github.AaronAA0721.villageragent.ai;

import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** Transient execution/perception state; all timestamps are server game ticks. */
public final class BrainRuntime {
    public boolean nightRest;
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
    public final TaskExecution tasks = new TaskExecution();
    public int farmOpportunities;
    public BlockPos rememberedFarm;
    public final java.util.Map<BlockPos, Long> farmRevisitAfter = new java.util.HashMap<>();
    public final java.util.Map<BlockPos, Long> rejectedJobSites = new java.util.HashMap<>();
    public long lastFarmMemoryScan = -200;

    public UUID socialPartner;
    public String lastFinishedType = "";
    public long lastFinishedTick;
}
