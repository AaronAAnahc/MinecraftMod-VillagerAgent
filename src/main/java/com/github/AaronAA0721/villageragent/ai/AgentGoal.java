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
    // ── Timed priority boost (scheduled goals) ──
    /** Game tick at which this goal's effective priority is temporarily raised to
     *  {@link #scheduledPriority}. Before that tick, the goal uses its normal priority.
     *  {@code Long.MAX_VALUE} means no schedule. Combined with the tether system, this
     *  lets a villager come back to a workstation after a wait without a dedicated timer. */
    private long scheduledTick = Long.MAX_VALUE;
    /** Priority applied once {@code scheduledTick} is reached. Should be below combat (9) and
     *  rest (8); the recommended value is 7 ("time-sensitive but not life-threatening"). */
    private int scheduledPriority = 7;
    public long getScheduledTick() { return scheduledTick; }
    public void setScheduledTick(long tick) { this.scheduledTick = tick; }
    public int getScheduledPriority() { return scheduledPriority; }
    public void setScheduledPriority(int priority) { this.scheduledPriority = priority; }
    /** Effective priority: the higher of normal and scheduled (if the time has come). */
    public int effectivePriority(long gameTick) {
        if (scheduledTick != Long.MAX_VALUE && gameTick >= scheduledTick)
            return Math.max(priority, scheduledPriority);
        return priority;
    }
    /** Appointment mode for {@link #scheduledTick}: the goal is not pickable at all before that tick
     *  (instead of merely running at normal priority). Set for LLM goals with an "at"/"in_seconds" time. */
    private boolean holdUntilScheduled;
    public boolean isHoldUntilScheduled() { return holdUntilScheduled; }
    public void setHoldUntilScheduled(boolean hold) { this.holdUntilScheduled = hold; }
    /** True while an appointment goal is still waiting for its start tick. */
    public boolean isHeldUntilScheduled(long gameTick) {
        return holdUntilScheduled && scheduledTick != Long.MAX_VALUE && gameTick < scheduledTick;
    }
    /** True once an appointment goal's start tick has come: it is booked and goes first when the villager is free. */
    public boolean isScheduledDue(long gameTick) {
        return holdUntilScheduled && scheduledTick != Long.MAX_VALUE && gameTick >= scheduledTick;
    }
    /**
     * Called when the scheduler hands this goal the running slot. An appointment's deadline is a
     * <em>start-by</em> deadline: once it has started it must not expire mid-way.
     */
    public void markStarted() {
        if (holdUntilScheduled && lifecycle == Lifecycle.EXPIRING) {
            lifecycle = Lifecycle.ONE_SHOT;
            deadlineTick = Long.MAX_VALUE;
        }
    }

    // ── Goal sequence ("sequence"): ordered steps run back-to-back by SequenceSkill ──
    /** Ordered child goals; non-empty only for a {@code sequence} goal. Steps are never roots. */
    private java.util.List<AgentGoal> steps = new java.util.ArrayList<>();
    /** Index of the step currently being executed (== steps.size() when all are done). */
    private int stepIndex;
    public java.util.List<AgentGoal> getSteps() { return steps; }
    public boolean hasSteps() { return !steps.isEmpty(); }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int index) { stepIndex = Math.max(0, index); }
    /** The step to run now, or null when the sequence is finished. */
    public AgentGoal currentStep() { return stepIndex < steps.size() ? steps.get(stepIndex) : null; }

    // ── Invitation ("invite"): visit each invitee and propose a shared plan ──
    private java.util.List<UUID> invitees = new java.util.ArrayList<>();
    private int inviteIndex;
    /** The activity being proposed (single goal or sequence, usually timed). Copied into each brain that accepts. */
    private AgentGoal invitePlan;
    private String inviteMessage;
    public java.util.List<UUID> getInvitees() { return invitees; }
    public int getInviteIndex() { return inviteIndex; }
    public void setInviteIndex(int index) { inviteIndex = Math.max(0, index); }
    public AgentGoal getInvitePlan() { return invitePlan; }
    public void setInvitePlan(AgentGoal plan) { invitePlan = plan; }
    public String getInviteMessage() { return inviteMessage; }
    public void setInviteMessage(String message) { inviteMessage = message; }
    /** Shared id of one group activity: every villager's copy of an accepted plan carries the same id. */
    private UUID groupId;
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID id) { groupId = id; }
    /** Short human-readable name of a planned activity ("Dinner", "Cook meat"); shown in the goals panel. */
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null || title.trim().isEmpty() ? null : title.trim(); }
    /**
     * For an invitation's plan: true when the inviter takes part too ({@code invite}: "dinner together"),
     * false when the inviter only asked the others to do it ({@code ask}: "please cook in an hour").
     */
    private boolean shared;
    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }
    /** Who proposed the plan (the inviter or asker); in a shared plan the others follow this villager, e.g. on a stroll. */
    private UUID leaderId;
    public UUID getLeaderId() { return leaderId; }
    public void setLeaderId(UUID id) { leaderId = id; }

    // ── Activity end ("until"/"for_minutes"): an appointment that lasts until a set tick ──
    /** Tick at which a timed activity ends ({@code Long.MAX_VALUE} = ends when its steps are done). */
    private long endTick = Long.MAX_VALUE;
    public long getEndTick() { return endTick; }
    public void setEndTick(long tick) { endTick = tick; }
    public boolean hasEndTime() { return endTick != Long.MAX_VALUE; }
    /** True once the activity's end time has passed. */
    public boolean isPastEnd(long gameTick) { return endTick != Long.MAX_VALUE && gameTick >= endTick; }
    /**
     * An "activity" is a plan that is about being somewhere with someone for a while: it has an end
     * time or belongs to a group. Activities wait out WAITING steps and gather before starting.
     */
    public boolean isActivity() { return endTick != Long.MAX_VALUE || groupId != null; }
    /**
     * Game tick at which this goal (or sequence step) first started running; -1 = not yet. Lives on the
     * goal, not on a task frame, so interruptions (rest, a player chat) do not restart its clock.
     */
    private long startedTick = -1;
    public long getStartedTick() { return startedTick; }
    public void setStartedTick(long tick) { startedTick = tick; }

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
    /** Runtime-only: any tool/food of this supply category satisfies the target (see SupplyPolicy). */
    private String requestCategory;
    public String getRequestCategory() { return requestCategory; }
    public void setRequestCategory(String category) { requestCategory = category; }
    /** Runtime-only: remembered request spec key and units, as last read on the board (supply responses). */
    private String requestSpec;
    private int requestUnits;
    public String getRequestSpec() { return requestSpec; }
    public void setRequestSpec(String key) { requestSpec = key; }
    public int getRequestUnits() { return requestUnits; }
    public void setRequestUnits(int units) { requestUnits = Math.max(0, units); }
    /** Runtime-only: the delivery meeting this supply task is waiting on after claiming its request. */
    private UUID claimId;
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID id) { claimId = id; }
    /** Runtime-only: acquisition may produce but must not publish a request for this exact output. */
    private boolean selfProductionOnly;
    public boolean isSelfProductionOnly() { return selfProductionOnly; }
    public void setSelfProductionOnly(boolean value) { selfProductionOnly = value; }
    private int failureCount;
    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID id) { workOrderId = id; }
    public int recordFailure() { return ++failureCount; }
    public int getFailureCount() { return failureCount; }
    public void resetFailures() { failureCount = 0; }

    // ── Child-goal dependency: acquire blocks itself while a spawned child (craft/trade) runs ──
    /** ID of a child goal that must complete before this goal re-enters scheduling.
     *  Null means no active child dependency. Set by AcquireItemSkill when it spawns
     *  a production/trade goal; cleared when that child completes or is removed. */
    private String childGoalType;
    private String childTargetItem;
    public String getChildGoalType() { return childGoalType; }
    public String getChildTargetItem() { return childTargetItem; }
    public void setChildDependency(String goalType, String targetItem) {
        this.childGoalType = goalType; this.childTargetItem = targetItem;
    }
    public void clearChildDependency() { childGoalType = null; childTargetItem = null; }
    public boolean hasChildDependency() { return childGoalType != null; }

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
    public void setDescription(String description) { this.description = description; }
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
