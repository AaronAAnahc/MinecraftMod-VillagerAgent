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
            case "farm": case "craft": case "fulfill_order": floor = 7; break;
            case "restock": case "acquire_item": case "gather": case "trade": floor = 6; break;
            default: return;
        }
        goal.setPriority(Math.max(goal.getPriority(), floor));
    }

    public static boolean canYieldBetweenActions(AgentGoal current) {
        return current == null || !com.github.AaronAA0721.villageragent.ai.behavior.BehaviorExecutor.isComposite(current.getGoalType());
    }

    public static boolean shouldPreempt(AgentGoal current, AgentGoal candidate) {
        return candidate != null && (current == null
                || (isFallback(current) && !isFallback(candidate))
                || (candidate.getPriority() >= 9 && candidate.getPriority() > current.getPriority()));
    }
    public static boolean shouldPreempt(VillagerAgentData agent, AgentGoal current, AgentGoal candidate) {
        return shouldPreempt(current, candidate) || (candidate != null && current != null
                && current.getPriority() < 9 && !com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(current)
                && com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(candidate)
                && (agent.brain().nightRest || agent.getFatigue() >= 80));
    }
    public static double utility(VillagerAgentData a, AgentGoal g, long now) {
        if (g.isCompleted() || g.isSuspended() || g.isExpired(now) || g.isOnCooldown(now)) return 0;
        double u = Math.max(1, g.getPriority()) * Math.max(0.1, g.getWeight());
        switch (g.getGoalType()) {
            case "farm": u *= Math.min(4, (a.brain().farmOpportunities + (a.brain().rememberedFarm != null ? 3 : 0)) / 3.0); break;
            case "rest": case "sleep":
                if (a.getFatigue() < 45) return 0;
                if (a.brain().nightRest) u *= 4;
                u *= Math.max(1, Math.pow(a.getFatigue() / 35.0, 2)); break;
            case "socialize":
                if (a.brain().socialPartner == null || now - a.getLastSocialTick() < 400 || a.isSocializing()) return 0;
                u *= Math.min(5, (now - a.getLastSocialTick()) / 600.0);
                String personality = String.valueOf(a.getPersonality()).toLowerCase(Locale.ROOT);
                if (personality.contains("friendly") || personality.contains("cheerful")) u *= 1.5;
                break;
            case "find_job_site":
                if (a.getResourceProfession() != com.github.AaronAA0721.villageragent.ai.work.ResourceProfession.NONE) return 0;
                break;
            case "craft":
                if (g.getLifecycle() == AgentGoal.Lifecycle.REPEATABLE && g.getTargetItem() != null
                        && com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(a, g.getTargetItem()) >= 8) return 0;
                break;
            case "restock":
                boolean missing = false;
                for (String item : g.getOutcomeItems()) {
                    if (com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(a, item) == 0) { missing = true; break; }
                }
                if (!missing) return 0;
                break;
            case "fulfill_order": case "deliver_trade": u *= 2.5; break;
            case "wander": u *= a.getFatigue() > 60 ? 0.25 : 1; break;
            default: break;
        }
        if (g.getGoalType().equals(a.brain().lastFinishedType) && now - a.brain().lastFinishedTick < 600) u *= 0.3;
        return u;
    }
    private static boolean isFallback(AgentGoal goal) {
        return "wander".equals(goal.getGoalType()) && goal.getPriority() < 9;
    }

    public static AgentGoal pickNextGoal(VillagerAgentData a, List<AgentGoal> goals, long now) {
        if (a.brain().nightRest || a.getFatigue() >= 80) {
            AgentGoal rest = null;
            boolean emergency = false;
            for (AgentGoal g : goals) {
                if (utility(a, g, now) <= 0) continue;
                if (g.getPriority() >= 9) emergency = true;
                if (com.github.AaronAA0721.villageragent.ai.behavior.SleepSkill.isSleepGoal(g)) rest = g;
            }
            if (rest != null && !emergency) return rest;
        }
        return pickScored(goals, g -> utility(a, g, now), RANDOM.nextDouble());
    }

    /** Caller supplies only executable goals. Idle wandering runs only when all other scores are zero. */
    static AgentGoal pickScored(List<AgentGoal> goals,
                               java.util.function.ToDoubleFunction<AgentGoal> scorer, double sample) {
        Map<AgentGoal, Double> scores = new LinkedHashMap<>();
        AgentGoal emergency = null;
        boolean hasPurpose = false;
        for (AgentGoal g : goals) {
            double u = scorer.applyAsDouble(g);
            if (u <= 0) continue;
            scores.put(g, u);
            if (!isFallback(g)) hasPurpose = true;
            if (g.getPriority() >= 9 && (emergency == null || g.getPriority() > emergency.getPriority())) emergency = g;
        }
        if (emergency != null) return emergency;
        double total = 0;
        for (Map.Entry<AgentGoal, Double> entry : scores.entrySet()) {
            if (!hasPurpose || !isFallback(entry.getKey())) total += entry.getValue();
        }
        double pick = sample * total;
        AgentGoal last = null;
        for (Map.Entry<AgentGoal, Double> entry : scores.entrySet()) {
            if (hasPurpose && isFallback(entry.getKey())) continue;
            last = entry.getKey();
            pick -= entry.getValue();
            if (pick < 0) return last;
        }
        return last;
    }
}
