package com.github.AaronAA0721.villageragent.ai;

import java.util.*;

/** State-based utility sampling with nighttime rest precedence over ordinary work. */
public final class GoalScheduler {
    private static final Random RANDOM = new Random();
    private GoalScheduler() {}
    public static void expireGoals(List<AgentGoal> goals, long now) {
        // Migrate persisted tasks as well as newly generated tasks; preserve explicit emergencies.
        for (AgentGoal g : goals) applyWorkPriority(g);
        goals.removeIf(g -> (g.isCompleted() && g.getLifecycle() != AgentGoal.Lifecycle.REPEATABLE) || g.isExpired(now));
    }
    public static void applyWorkPriority(AgentGoal goal) {
        int floor;
        switch (goal.getGoalType()) {
            case "find_job_site": case "deliver_trade": floor = 8; break;
            case "farm": case "craft": case "fulfill_order": case "lumberjack_work": case "miner_work": floor = 7; break;
            case "place_block": case "place_near_self": floor = 7; break;
            case "restock": case "acquire_item": case "gather": case "collect_smelt": floor = 6; break;
            default: return;
        }
        goal.setPriority(Math.max(goal.getPriority(), floor));
    }

    /**
     * Immediate interruption of the running goal: wandering yields to anything with a purpose, and emergencies
     * (priority 9+) take over anything less urgent. A booked meeting never interrupts here; it waits for the
     * running task to end or reach a yield point (see {@link GoalCommitment}).
     */
    public static boolean shouldPreempt(AgentGoal current, AgentGoal candidate) {
        if (current != null && candidate != null && "meet_trade".equals(candidate.getGoalType())) return false;
        return candidate != null && (current == null
                || (isWander(current) && !isIdle(candidate))
                || (candidate.getPriority() >= 9 && candidate.getPriority() > current.getPriority()));
    }
    public static final float REST_MIN_FATIGUE = 40;
    public static final float REST_INTERRUPT_FATIGUE = 80;
    public static boolean isNonUrgentRest(VillagerAgentData agent, AgentGoal goal) {
        return com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(goal)
                && agent.getFatigue() < REST_INTERRUPT_FATIGUE;
    }
    /** Night curfew (config) is active for this villager's current scheduler cycle. */
    public static boolean curfewNight(VillagerAgentData agent) {
        return agent.brain().nightRest && com.github.AaronAA0721.villageragent.config.ModConfig.NIGHT_CURFEW.get();
    }
    public static boolean shouldPreempt(VillagerAgentData agent, AgentGoal current, AgentGoal candidate) {
        // Curfew: bed time interrupts any ordinary (non-emergency) task.
        if (curfewNight(agent) && candidate != null && current != null
                && com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(candidate)
                && !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(current)
                && current.getPriority() < 9) return true;
        if (isNonUrgentRest(agent, candidate)) return false;
        return shouldPreempt(current, candidate) || (candidate != null && current != null
                && current.getPriority() < 9 && !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(current)
                && com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(candidate)
                && agent.getFatigue() >= REST_INTERRUPT_FATIGUE);
    }
    public static double utility(VillagerAgentData a, AgentGoal g, long now) {
        if (g.isCompleted() || g.isSuspended() || g.isExpired(now) || g.isOnCooldown(now)) return 0;
        // An appointment (timed LLM goal) does not exist for the scheduler before its start tick.
        if (g.isHeldUntilScheduled(now)) return 0;
        // A task blocked on a missing item waits while its acquisition for that item is still under way,
        // instead of re-failing on the same missing input every cooldown.
        if (waitingOnAcquisition(a, g)) return 0;
        double u = Math.max(1, g.effectivePriority(now)) * Math.max(0.1, g.getWeight());

        // Each skill defines its own chanceFactor(); GoalScheduler no longer hardcodes per-type logic.
        com.github.AaronAA0721.villageragent.ai.behavior.VillagerSkill skill =
                com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor.skillForGoalType(g.getGoalType());
        double factor = skill != null ? skill.chanceFactor(a, g, now) : 1.0;
        if (factor <= 0) return 0;
        u *= factor;

        // Recency factor: quadratic recovery curve  factor = 0.3 + 0.7 * (elapsed/T)^2
        // T = recovery time, scaled by how long the last session was (longer work → longer rest).
        u *= recencyFactor(a, g.getGoalType(), now);
        return u;
    }
    // ── Recency: quadratic recovery  f = 0.3 + 0.7 * (elapsed / T)^2 ──
    // T_MIN/T_MAX define the recovery window; actual T is interpolated by last session duration.
    private static final int T_MIN = 600;   // 30s — shortest recovery (after a brief session)
    private static final int T_MAX = 3600;  // 3min — longest recovery (after a full-length session)
    private static final int SESSION_NORM = 600; // session ticks at which T reaches T_MAX
    private static final double FACTOR_MIN = 0.3;

    /**
     * Quadratic recovery factor for a goal type: 0.3 right after finishing,
     * gradually returning to 1.0 over T ticks (T depends on session length).
     * Types never finished return 1.0.
     */
    static double recencyFactor(VillagerAgentData a, String goalType, long now) {
        Long finishedAt = a.brain().lastFinishedTicks.get(goalType);
        if (finishedAt == null) {
            return 1.0;
        }
        long elapsed = now - finishedAt;
        if (elapsed < 0) return 1.0;

        // Recovery time T: interpolated by last session duration.
        int sessionDur = a.brain().lastSessionDurations.getOrDefault(goalType, 0);
        double ratio = Math.min(1.0, (double) sessionDur / SESSION_NORM);
        double T = T_MIN + (T_MAX - T_MIN) * ratio;

        if (elapsed >= T) return 1.0;

        // Quadratic: slow start, fast finish  f = 0.3 + 0.7 * (elapsed/T)^2
        double progress = elapsed / T;
        return FACTOR_MIN + (1.0 - FACTOR_MIN) * progress * progress;
    }

    /** Idle wandering. */
    static boolean isWander(AgentGoal goal) {
        return "wander".equals(goal.getGoalType()) && goal.getPriority() < 9;
    }
    /**
     * Idle activities: wandering and the routine notice-board check. They are chosen only when nothing with a
     * purpose is available, and then compete with each other by score.
     */
    public static boolean isIdle(AgentGoal goal) {
        if (isWander(goal)) return true;
        return "trade".equals(goal.getGoalType()) && goal.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE
                && goal.getPriority() < 9;
    }
    /**
     * Booked goals that take precedence over ordinary utility sampling once the villager is free:
     * a trade meeting (a partner is waiting) or a timed goal whose start time has come.
     */
    public static boolean isAppointment(AgentGoal g, long now) {
        return "meet_trade".equals(g.getGoalType()) || g.isScheduledDue(now);
    }

    /** {@code g} last stopped on a missing item and a live acquire_item for that item still exists. */
    static boolean waitingOnAcquisition(VillagerAgentData a, AgentGoal g) {
        if ("acquire_item".equals(g.getGoalType())) return false;
        com.github.AaronAA0721.villageragent.ai.behavior.Failure f = g.getLastFailure();
        if (f == null || f.reason != com.github.AaronAA0721.villageragent.ai.behavior.FailureReason.MISSING_ITEM
                || f.itemId == null) return false;
        for (AgentGoal other : a.getGoals()) {
            if (other == g || other.isCompleted() || !"acquire_item".equals(other.getGoalType())) continue;
            if (f.itemId.equals(other.getTargetItem()) || f.itemId.equals(other.getRequestCategory())) return true;
        }
        return false;
    }

    public static AgentGoal pickNextGoal(VillagerAgentData a, List<AgentGoal> goals, long now) {
        if (curfewNight(a)) {
            AgentGoal rest = null;
            boolean emergency = false;
            for (AgentGoal g : goals) {
                if (utility(a, g, now) <= 0) continue;
                if (g.getPriority() >= 9 && !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g)) emergency = true;
                if (com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g)) rest = g;
            }
            if (rest != null && !emergency) return rest;
        }
        if (a.getFatigue() >= REST_INTERRUPT_FATIGUE) {
            AgentGoal rest = null;
            boolean emergency = false;
            for (AgentGoal g : goals) {
                if (utility(a, g, now) <= 0) continue;
                if (g.getPriority() >= 9) emergency = true;
                if (com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g)) rest = g;
            }
            if (rest != null && !emergency) return rest;
        }
        // A booked meeting or a due appointment goes first once the villager is free.
        for (AgentGoal g : goals) {
            if (isAppointment(g, now) && utility(a, g, now) > 0) {
                boolean emergency = false;
                for (AgentGoal e : goals) if (e.getPriority() >= 9 && utility(a, e, now) > 0) emergency = true;
                if (!emergency) return g;
            }
        }
        boolean importantWork = !a.brain().nightRest && goals.stream().anyMatch(g -> g.getPriority() >= 6
                && !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g) && utility(a, g, now) > 0);
        return pickScored(goals, g -> importantWork && isNonUrgentRest(a, g) ? 0 : utility(a, g, now), RANDOM.nextDouble(),
                a.brain().nightRest || a.getFatigue() >= REST_INTERRUPT_FATIGUE);
    }

    /** Caller supplies only executable goals. Idle activities run only when all other scores are zero. */
    static AgentGoal pickScored(List<AgentGoal> goals,
                               java.util.function.ToDoubleFunction<AgentGoal> scorer, double sample) {
        return pickScored(goals, scorer, sample, true);
    }
    private static AgentGoal pickScored(List<AgentGoal> goals,
                                       java.util.function.ToDoubleFunction<AgentGoal> scorer, double sample,
                                       boolean restSuppressesWandering) {
        Map<AgentGoal, Double> scores = new LinkedHashMap<>();
        AgentGoal emergency = null;
        boolean hasPurpose = false;
        for (AgentGoal g : goals) {
            double u = scorer.applyAsDouble(g);
            if (u <= 0) continue;
            scores.put(g, u);
            if (!isIdle(g) && (restSuppressesWandering
                    || !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g))) hasPurpose = true;
            if (g.getPriority() >= 9 && (emergency == null || g.getPriority() > emergency.getPriority())) emergency = g;
        }
        if (emergency != null) return emergency;
        double total = 0;
        for (Map.Entry<AgentGoal, Double> entry : scores.entrySet()) {
            if (!hasPurpose || !isIdle(entry.getKey())) total += entry.getValue();
        }
        double pick = sample * total;
        AgentGoal last = null;
        for (Map.Entry<AgentGoal, Double> entry : scores.entrySet()) {
            if (hasPurpose && isIdle(entry.getKey())) continue;
            last = entry.getKey();
            pick -= entry.getValue();
            if (pick < 0) return last;
        }
        return last;
    }
}
