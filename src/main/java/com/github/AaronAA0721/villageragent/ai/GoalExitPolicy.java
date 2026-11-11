package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;

/** Bounded retries shared by every skill, including repeatable routines. */
public final class GoalExitPolicy {
    public static final int MAX_FAILURES = 3;
    public static final long RETIRED_ROUTINE_DELAY = 1200;

    public static boolean retain(AgentGoal goal, SkillResult result, long now) {
        if (result == SkillResult.DELETE) return false;
        if (result == SkillResult.SUCCESS) {
            goal.resetFailures();
            if (goal.getLifecycle() != AgentGoal.Lifecycle.REPEATABLE) return false;
            goal.setTargetLocation(null);
            // A routine with its own short cooldown (e.g. the 40-tick wander pause) keeps it; 0 means 100.
            goal.setCooldownUntilTick(now + (goal.getCooldownTicks() > 0
                    ? Math.max(20, goal.getCooldownTicks()) : 100));
        } else if (result == SkillResult.WAITING
                || (result == SkillResult.BLOCKED && goal.getLastFailure() != null
                    && goal.getLastFailure().reason.isItemRecoverable())) {
            // Waiting for stock or another agent is not evidence of an unreachable destination.
            goal.setCooldownUntilTick(now + 200);
        } else {
            if (goal.recordFailure() >= MAX_FAILURES) return false;
            goal.setCooldownUntilTick(now + (goal.getLastFailure() != null
                    && goal.getLastFailure().reason == com.github.AaronAA0721.villageragent.ai.behavior.FailureReason.UNREACHABLE
                    ? 200 : Math.max(200, goal.getCooldownTicks())));
        }
        goal.setCooldownUntilTick(Math.max(goal.getCooldownUntilTick(), now + goal.getMinimumExitCooldownTicks()));
        goal.setCompleted(false);
        return true;
    }

    private GoalExitPolicy() {}
}
