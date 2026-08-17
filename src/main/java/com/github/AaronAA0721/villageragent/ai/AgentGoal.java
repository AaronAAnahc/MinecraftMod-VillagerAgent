package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Represents a goal that an AI villager wants to accomplish
 */
public class AgentGoal {
    private String goalType; // "gather", "craft", "trade", "build", "socialize", etc.
    private String description;
    private int priority; // 1-10, higher is more important
    private BlockPos targetLocation;
    private String targetItem;
    private int targetQuantity;
    private UUID targetEntityId;     // specific player/villager UUID (converse)
    private String targetProfession; // profession filter, e.g. "toolsmith" (converse)
    private String targetKind;       // "player" | "villager" (converse)
    private String targetBuildingType; // building type filter, e.g. "house"/"cave_house"/"any" (goto)
    private String farmMode;            // for "farm" goals: "maintain" | "reclaim" (null = auto)
    private boolean completed;
    private long createdTime;
    /** How much the villager cares about this goal (1-10). Drives daily pruning + boosts. */
    private int importance = 5;

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

    /** For "farm" goals: "maintain" (reverted-old-farmland) or "reclaim" (open new land). */
    public String getFarmMode() { return farmMode; }
    public void setFarmMode(String mode) { this.farmMode = mode; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public long getCreatedTime() { return createdTime; }

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

