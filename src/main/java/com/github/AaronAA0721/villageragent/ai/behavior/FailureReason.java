package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;

/**
 * Predefined reasons a todo (goal) failed to start or continue.
 *
 * <p>Per the failure-driven re-planning design ({@code docs/failure_recovery.md}): every goal that
 * cannot start / continue must record one of these instead of silently skipping or hard-coding a
 * fallback. {@code FailureRecoveryPlanner} then maps a reason to a concrete recovery action.
 *
 * <p><b>Design note (v2 simplification):</b> {@code MISSING_TOOL} and {@code MISSING_ITEM} are
 * <em>unified</em> — a tool is just an item the villager doesn't have. A single {@code MISSING_ITEM}
 * reason carries the tool/item registry name in {@link Failure#itemId}, and the same three-step
 * recovery chain (find existing todo → craft → trade) handles both.
 */
public enum FailureReason {
    /** Missing an item or tool (hoe, seeds, iron ingot…). {@code Failure.itemId} names it. */
    MISSING_ITEM,
    /** Capability level too low. {@code Failure.capability} + {@code Failure.requiredLevel}. */
    CAPABILITY_TOO_LOW,
    /** No job-site memory — cannot restock or craft at a workstation. */
    NO_JOB_SITE,
    /** Target item has no resolvable recipe (vanilla or custom). */
    NO_RECIPE,
    /** No available bed (sleep). */
    NO_BED,
    /** Bed present but occupied by another villager. */
    BED_OCCUPIED,
    /** Not night-time (sleep) — usually ends the goal, no recovery. */
    NOT_NIGHT,
    /** Path destination unreachable. */
    UNREACHABLE,
    /** Catch-all; logged only. */
    UNKNOWN;

    /**
     * Convenience: does this reason describe a missing-item situation that the three-step recovery
     * chain (existing-todo → craft → trade) can address?
     */
    public boolean isItemRecoverable() {
        return this == MISSING_ITEM;
    }

    /**
     * Convenience: does this reason describe a capability shortfall that recovery cannot fix
     * (the villager must level up first)?
     */
    public boolean isCapabilityGap() {
        return this == CAPABILITY_TOO_LOW;
    }

    /**
     * Build a minimal {@link Failure} for this reason.
     */
    public Failure with(String itemId) {
        return new Failure(this, itemId, 1, null, 0);
    }

    public Failure with(String itemId, int qty) {
        return new Failure(this, itemId, qty, null, 0);
    }

    public Failure with(CapabilityId cap, int requiredLevel) {
        return new Failure(this, null, 1, cap, requiredLevel);
    }
}
