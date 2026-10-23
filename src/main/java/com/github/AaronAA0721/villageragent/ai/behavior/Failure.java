package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;

/**
 * A single recorded failure of a todo (goal). Read by {@link FailureRecoveryPlanner} to decide
 * which recovery action to inject.
 *
 * <p>Immutable. Stored on {@code AgentGoal.lastFailure} (runtime only — not NBT-persisted; a
 * routine goal re-triggers failure, and thus re-recovery, on the next session).
 */
public final class Failure {
    public final FailureReason reason;
    /** Item / tool registry name (e.g. {@code "minecraft:iron_hoe"}). Null for non-item reasons. */
    public final String itemId;
    /** How many are missing (default 1). */
    public final int quantity;
    /** Capability that was too low, if {@link #reason} is {@link FailureReason#CAPABILITY_TOO_LOW}. */
    public final CapabilityId capability;
    /** Required level for {@link #capability}. */
    public final int requiredLevel;

    public Failure(FailureReason reason, String itemId, int quantity,
                   CapabilityId capability, int requiredLevel) {
        this.reason = reason;
        this.itemId = itemId;
        this.quantity = Math.max(1, quantity);
        this.capability = capability;
        this.requiredLevel = requiredLevel;
    }

    /** De-duplication key: same reason + same item → same recovery (no re-injection). */
    public String recoveryKey() {
        return reason + ":" + (itemId == null ? "" : itemId);
    }

    @Override
    public String toString() {
        if (reason == FailureReason.CAPABILITY_TOO_LOW) {
            return reason + "(" + capability + " needs " + requiredLevel + ")";
        }
        return reason + "(" + itemId + " x" + quantity + ")";
    }
}
