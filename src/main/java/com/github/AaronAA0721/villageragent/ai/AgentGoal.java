package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.Failure;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a goal that an AI villager wants to accomplish
 */
public class AgentGoal {

    /**
     * How a goal leaves the list. {@link #ROUTINE} goals survive completion (the routine
     * generator refreshes them by cycle); {@link #ONE_SHOT} goals are removed on completion;
     * {@link #EXPIRING} goals are removed on completion <em>or</em> once {@link #deadlineTick}
     * passes.
     */
    public enum Lifecycle { ONE_SHOT, REPEATABLE, EXPIRING }

    public enum Source { RULE, LLM, PLAYER }
    private Source source = Source.RULE;
    public Source getSource() { return source == null ? Source.RULE : source; }
    public void setSource(Source source) { this.source = source; }

    private String goalType; // "gather", "craft", "trade", "build", "socialize", etc.
    private String description;
    private int priority; // 1-10, higher is more important
    private BlockPos targetLocation;
    private double arrivalDistanceSq = 16.0;
    public double getArrivalDistanceSq() { return arrivalDistanceSq; }
    public void setArrivalDistanceSq(double value) { arrivalDistanceSq = Math.max(0.25, value); }
    private String targetItem;
    private int targetQuantity;
    private UUID targetEntityId;     // specific player/villager UUID (converse)
    private String targetProfession; // profession filter, e.g. "toolsmith" (converse)
    private String targetKind;       // "player" | "villager" (converse)
    private String targetBuildingType; // building type filter, e.g. "house"/"cave_house"/"any" (goto)
    private boolean completed;
    private long createdTime;
    /** How much the villager cares about this goal (1-10). Drives daily pruning + boosts. */
    private int importance = 5;

    // ── Scheduling / lifecycle (added by capability_pool_plan.md §9) ──
    /** How this goal leaves the list (ROUTINE / ONE_SHOT / EXPIRING). */
    private Lifecycle lifecycle = Lifecycle.ONE_SHOT;
    /** Game tick past which an EXPIRING goal is dropped (only meaningful for EXPIRING). */
    private long deadlineTick = Long.MAX_VALUE;
    /** True while a higher-priority goal preempted this one — it waits, then resumes. */
    private boolean suspended = false;
    /** Scheduling weight used by {@code GoalScheduler} within a priority band. */
    private double weight = 1.0;
    /** Routine-window id that injected this goal (null for non-routine). Used to evict stale
     *  routine goals when the active time window switches. Set by {@code RoutineScheduler}. */
    private String routineWindowId = null;

    // ── Repeatable-goal support (REPEATABLE lifecycle) ──
    /** Cooldown length in ticks applied after a REPEATABLE goal succeeds/fails; it re-enters
     *  the pick pool only once {@code cooldownUntilTick} has passed. 0 = no cooldown. */
    private int cooldownTicks = 0;
    private int minimumExitCooldownTicks;
    public int getMinimumExitCooldownTicks() { return minimumExitCooldownTicks; }
    public void setMinimumExitCooldownTicks(int ticks) { minimumExitCooldownTicks = Math.max(0, ticks); }
    /** Absolute game tick at which a REPEATABLE goal becomes available again. */
    private long cooldownUntilTick = 0;

    // ── Time-of-day scheduling weight (REPEATABLE) ──
    /** Day-phase scheduling weight, written at seed time by {@code RoutineScheduler}. There is no
     *  global runtime weight table anymore — the goal carries its own day/night weights, so it is
     *  self-describing. Drives soft-sampling availability per phase; <= 0 means off-phase. */
    private double dayWeight = 0.0;
    /** Night-phase scheduling weight (see {@link #dayWeight}). */
    private double nightWeight = 0.0;

    // ── Failure-driven re-planning (docs/failure_recovery.md) ──
    /** Items this goal is expected to put into the villager's inventory on success (registry
     *  names, e.g. {@code "minecraft:iron_hoe"}). A {@code restock} goal lists its profession's
     *  starter items; a {@code craft}/{@code acquire_item} goal lists its {@code targetItem}.
     *  Used by {@code FailureRecoveryPlanner}: when a goal fails with MISSING_ITEM(X), the
     *  planner scans existing goals' outcome sets for X and boosts that goal's priority instead
     *  of spawning a duplicate. Empty set = "outcome not item-producing" (e.g. sleep, move). */
    private Set<String> outcomeItems = new HashSet<>();
    /** Most recent failure recorded by the executing skill (runtime only; not NBT-persisted).
     *  Read by {@code FailureRecoveryPlanner} after a BLOCKED/FAIL return. */
    private Failure lastFailure = null;
    /** Runtime progress evidence, independent of frame rebuilds and goal creation wall time. */
    private long progressVersion;
    public long getProgressVersion() { return progressVersion; }
    public void markProgress() { progressVersion++; }
    private UUID methodAttempt;
    public UUID getMethodAttempt() { return methodAttempt; }
    public void setMethodAttempt(UUID owner) { methodAttempt = owner; }
    private UUID workOrderId;
    private UUID tradeMessageId;
    public UUID getTradeMessageId() { return tradeMessageId; }
    public void setTradeMessageId(UUID id) { tradeMessageId = id; }
    private int failureCount;
    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID id) { workOrderId = id; }
    public int recordFailure() { return ++failureCount; }
    public void resetFailures() { failureCount = 0; }

    public AgentGoal(String goalType, String description, int priority) {
        this.goalType = goalType;
        this.description = description;
        this.priority = priority;
        this.completed = false;
        this.createdTime = System.currentTimeMillis();
    }

    // Getters and setters
    public String getGoalType() { return goalType; }
    public String getDescription() { return description; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public BlockPos getTargetLocation() { return targetLocation; }
    public void setTargetLocation(BlockPos pos) { this.targetLocation = pos; }
    public String getTargetItem() { return targetItem; }
    public void setTargetItem(String item) { this.targetItem = item; }
    public int getTargetQuantity() { return targetQuantity; }
    public void setTargetQuantity(int quantity) { this.targetQuantity = quantity; }
    public UUID getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(UUID id) { this.targetEntityId = id; }
    public String getTargetProfession() { return targetProfession; }
    public void setTargetProfession(String profession) { this.targetProfession = profession; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String kind) { this.targetKind = kind; }
    public String getTargetBuildingType() { return targetBuildingType; }
    public void setTargetBuildingType(String buildingType) { this.targetBuildingType = buildingType; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public long getCreatedTime() { return createdTime; }

    // ── Lifecycle / scheduling accessors ──
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public long getDeadlineTick() { return deadlineTick; }
    public void setDeadlineTick(long deadlineTick) { this.deadlineTick = deadlineTick; }
    public boolean isExpired(long gameTick) {
        return lifecycle == Lifecycle.EXPIRING && deadlineTick != Long.MAX_VALUE && gameTick > deadlineTick;
    }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    /** Routine-window id (for eviction on window switch); null for non-routine goals. */
    public String getRoutineWindowId() { return routineWindowId; }
    public void setRoutineWindowId(String id) { this.routineWindowId = id; }

    public int getCooldownTicks() { return cooldownTicks; }
    public void setCooldownTicks(int ticks) { this.cooldownTicks = ticks; }
    public long getCooldownUntilTick() { return cooldownUntilTick; }
    public void setCooldownUntilTick(long tick) { this.cooldownUntilTick = tick; }
    /** True if a REPEATABLE goal is still cooling down and must not be picked. */
    public boolean isOnCooldown(long gameTick) {
        return cooldownUntilTick > gameTick;
    }

    public double getDayWeight() { return dayWeight; }
    public void setDayWeight(double w) { this.dayWeight = w; }
    public double getNightWeight() { return nightWeight; }
    public void setNightWeight(double w) { this.nightWeight = w; }

    /** Effective scheduling weight at this in-game time. Reads the per-goal day/night weights
     *  (seeded by {@code RoutineScheduler}); replaces the old global {@code SPECS} lookup table.
     *  <= 0 means this goal is off-phase right now (not pickable this phase). */
    public double phaseWeight(long dayTime) {
        long t = dayTime % 24_000L;
        boolean night = t >= 12541L && t <= 23458L; // mirror SleepSkill.isNightTime (vanilla sleep window)
        return night ? nightWeight : dayWeight;
    }

    // ── Failure-driven re-planning accessors ──
    /** Items this goal is expected to produce (registry names). See {@link #outcomeItems}. */
    public Set<String> getOutcomeItems() { return outcomeItems; }
    public void setOutcomeItems(Set<String> items) {
        this.outcomeItems = (items == null) ? new HashSet<>() : items;
    }
    /** Convenience: does this goal's outcome include {@code itemId}? */
    public boolean produces(String itemId) {
        return itemId != null && outcomeItems.contains(itemId);
    }
    public Failure getLastFailure() { return lastFailure; }
    public void setLastFailure(Failure f) { this.lastFailure = f; }

    /** Importance is clamped to 1-10. */
    public int getImportance() { return importance; }
    public void setImportance(int importance) {
        this.importance = Math.max(1, Math.min(10, importance));
    }

    /** Nudge this goal's importance up by 1 (capped at 10). Used when the villager thinks. */
    public void boostImportance() {
        this.importance = Math.min(10, this.importance + 1);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (Priority: %d, Importance: %d)",
                goalType, description, priority, importance);
    }
}
