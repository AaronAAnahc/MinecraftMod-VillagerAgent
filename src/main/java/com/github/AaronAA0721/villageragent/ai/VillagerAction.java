package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Represents an action a villager can take.
 * For world-interacting actions (HARVEST, GROW, ATTACK), the villager must walk to
 * the target before the action executes.
 */
public class VillagerAction {
    public enum ActionType {
        CRAFT,          // Craft an item at a workstation
        HARVEST,        // Harvest crops or resources
        GROW,           // Plant seeds or grow crops
        ATTACK,         // Attack a hostile entity
        IDLE,           // Do nothing for a time interval
        MOVE,           // Move to a location
        GATHER,         // Gather items from ground
        PLACE,          // Place a single block within 1 block of the villager
        BREAK,          // Break a single block within 1 block of the villager
        BUILD,          // Marker: villager is mid-way through a multi-block BuildJob
        FLEE,           // Flee from a threat while raising a rally alarm (waiting to charge)
        UNKNOWN         // Unknown action from LLM
    }

    /** Phases for actions that require the villager to walk somewhere first. */
    public enum ActionPhase {
        SEARCHING,      // Looking for a target block
        WALKING,        // Walking toward the target block
        ACTING,         // Close enough — performing the action
        WAITING         // Waiting for items to arrive (e.g. after harvest, before replant)
    }

    private ActionType actionType;
    private ActionPhase phase = ActionPhase.SEARCHING;
    private String description;
    private String targetRecipe;    // For CRAFT actions
    private String targetItem;      // For HARVEST/GATHER actions
    private int targetQuantity;     // For HARVEST/GATHER actions
    private long idleDuration;      // For IDLE actions (in ticks)
    private long createdTime;
    private BlockPos targetBlockPos; // The block the villager is walking toward
    private UUID targetEntityId;    // The entity the villager is targeting (for ATTACK)
    private int stuckTicks;         // How many ticks the villager has been unable to reach target
    /** True when this action was started by the goal dispatcher (as opposed to the daily schedule). */
    private boolean goalDriven = false;

    // ── World-interaction (PLACE / BREAK / BUILD) fields ──
    /** The legal standing cell the villager must occupy to reach targetBlockPos (Chebyshev <= 1). */
    private BlockPos standCell;
    /** The block type to place (for PLACE actions). */
    private Block placeBlock;
    /** Accumulated break progress in ticks (for BREAK actions). */
    private int breakProgress;
    /** Total ticks required to break the target with the currently equipped tool (for BREAK). */
    private int breakTargetTicks;
    /** Index of this step within the owning BuildJob (-1 if not part of a job). */
    private int jobCursor = -1;

    // Pending replant data (used after HARVEST to wait for seeds before replanting)
    private Block pendingReplantCrop;       // The crop type to replant (null = no pending replant)
    private BlockPos pendingReplantPos;     // The farmland position to replant on
    private int pendingReplantWaitTicks;    // How many ticks we've waited for seeds

    public VillagerAction(ActionType actionType, String description) {
        this.actionType = actionType;
        this.description = description;
        this.createdTime = System.currentTimeMillis();
    }

    // Getters and setters
    public ActionType getActionType() { return actionType; }
    public String getDescription() { return description; }
    public ActionPhase getPhase() { return phase; }
    public void setPhase(ActionPhase phase) { this.phase = phase; }
    public String getTargetRecipe() { return targetRecipe; }
    public void setTargetRecipe(String recipe) { this.targetRecipe = recipe; }
    public String getTargetItem() { return targetItem; }
    public void setTargetItem(String item) { this.targetItem = item; }
    public int getTargetQuantity() { return targetQuantity; }
    public void setTargetQuantity(int quantity) { this.targetQuantity = quantity; }
    public long getIdleDuration() { return idleDuration; }
    public void setIdleDuration(long duration) { this.idleDuration = duration; }
    public long getCreatedTime() { return createdTime; }
    public BlockPos getTargetBlockPos() { return targetBlockPos; }
    public void setTargetBlockPos(BlockPos pos) { this.targetBlockPos = pos; }
    public UUID getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(UUID id) { this.targetEntityId = id; }
    public int getStuckTicks() { return stuckTicks; }
    public void incrementStuckTicks() { this.stuckTicks++; }
    public void resetStuckTicks() { this.stuckTicks = 0; }

    public boolean isGoalDriven() { return goalDriven; }
    public void setGoalDriven(boolean goalDriven) { this.goalDriven = goalDriven; }

    // ── World-interaction accessors ──
    public BlockPos getStandCell() { return standCell; }
    public void setStandCell(BlockPos standCell) { this.standCell = standCell; }
    public Block getPlaceBlock() { return placeBlock; }
    public void setPlaceBlock(Block placeBlock) { this.placeBlock = placeBlock; }
    public int getBreakProgress() { return breakProgress; }
    public void setBreakProgress(int breakProgress) { this.breakProgress = breakProgress; }
    public void incrementBreakProgress() { this.breakProgress++; }
    public int getBreakTargetTicks() { return breakTargetTicks; }
    public void setBreakTargetTicks(int breakTargetTicks) { this.breakTargetTicks = breakTargetTicks; }
    public int getJobCursor() { return jobCursor; }
    public void setJobCursor(int jobCursor) { this.jobCursor = jobCursor; }

    // Pending replant accessors
    public Block getPendingReplantCrop() { return pendingReplantCrop; }
    public BlockPos getPendingReplantPos() { return pendingReplantPos; }
    public int getPendingReplantWaitTicks() { return pendingReplantWaitTicks; }
    public void incrementPendingReplantWaitTicks() { this.pendingReplantWaitTicks++; }
    public void setPendingReplant(Block crop, BlockPos farmlandPos) {
        this.pendingReplantCrop = crop;
        this.pendingReplantPos = farmlandPos;
        this.pendingReplantWaitTicks = 0;
    }

    @Override
    public String toString() {
        return String.format("[%s/%s] %s", actionType, phase, description);
    }
}

