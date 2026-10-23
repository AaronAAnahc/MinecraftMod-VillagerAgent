package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;

/** Regression checks for bounded retries and terminal results without starting a world. */
public final class GoalExitPolicyChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (AgentGoal.Lifecycle lifecycle : AgentGoal.Lifecycle.values()) {
            for (SkillResult result : new SkillResult[] {SkillResult.FAIL, SkillResult.BLOCKED}) {
                AgentGoal g = goal(lifecycle);
                for (int attempt = 1; attempt <= 3; attempt++) {
                    g.setCompleted(true); // Legacy skills mark failed walks completed too.
                    boolean retained = GoalExitPolicy.retain(g, result, attempt * 1000);
                    check(retained == (attempt < 3), lifecycle + " must retire on third " + result);
                    if (retained) {
                        check(!g.isCompleted(), "retry must be executable again");
                        check(g.isOnCooldown(attempt * 1000), "failed task yields to other goals");
                    }
                }
            }
            AgentGoal g = goal(lifecycle);
            check(!GoalExitPolicy.retain(g, SkillResult.DELETE, 0), "DELETE overrides repeatable lifecycle");
            g = goal(lifecycle);
            check(GoalExitPolicy.retain(g, SkillResult.SUCCESS, 0)
                    == (lifecycle == AgentGoal.Lifecycle.REPEATABLE), "only routines survive success");
        }
        AgentGoal routine = goal(AgentGoal.Lifecycle.REPEATABLE);
        GoalExitPolicy.retain(routine, SkillResult.FAIL, 0);
        GoalExitPolicy.retain(routine, SkillResult.SUCCESS, 1000);
        check(GoalExitPolicy.retain(routine, SkillResult.FAIL, 2000), "success resets failure streak");
        check(GoalExitPolicy.retain(routine, SkillResult.FAIL, 3000), "second failure after success is retryable");
        AgentGoal waiting = goal(AgentGoal.Lifecycle.ONE_SHOT);
        for (int i = 0; i < 10; i++)
            check(GoalExitPolicy.retain(waiting, SkillResult.WAITING, i * 1000), "supply waiting is not path failure");
        check(waiting.isOnCooldown(9000), "waiting releases executor and cools down");
        AgentGoal supplyBlocked = goal(AgentGoal.Lifecycle.ONE_SHOT);
        supplyBlocked.setLastFailure(com.github.AaronAA0721.villageragent.ai.behavior.FailureReason.MISSING_ITEM.with("minecraft:oak_log"));
        for (int i = 0; i < 10; i++) {
            check(GoalExitPolicy.retain(supplyBlocked, SkillResult.BLOCKED, i * 1000),
                    "a real supply dependency must survive while the supplier is working");
            check(supplyBlocked.isOnCooldown(i * 1000), "supply blocked work must yield");
        }
        supplyBlocked.setLastFailure(com.github.AaronAA0721.villageragent.ai.behavior.FailureReason.UNREACHABLE.with("target"));
        check(GoalExitPolicy.retain(supplyBlocked, SkillResult.FAIL, 11000), "supply waiting does not consume path retries");
        check(GoalExitPolicy.retain(supplyBlocked, SkillResult.FAIL, 12000), "second real path failure can retry");
        check(!GoalExitPolicy.retain(supplyBlocked, SkillResult.FAIL, 13000), "third real path failure retires task");
        AgentGoal job = new AgentGoal("find_job_site", "workstation", 4);
        job.setCooldownTicks(2000);
        job.setLastFailure(com.github.AaronAA0721.villageragent.ai.behavior.FailureReason.UNREACHABLE.with("site"));
        check(GoalExitPolicy.retain(job, SkillResult.FAIL, 1000), "job search retries after failed site");
        check(job.getCooldownUntilTick() == 1200, "failed route must not inherit long routine cooldown");
        System.out.println("GoalExitPolicyChecks: " + assertions + " assertions passed");
    }

    private static AgentGoal goal(AgentGoal.Lifecycle lifecycle) {
        AgentGoal g = new AgentGoal("wander", "test", 2);
        g.setLifecycle(lifecycle);
        g.setCooldownTicks(100);
        return g;
    }
}
