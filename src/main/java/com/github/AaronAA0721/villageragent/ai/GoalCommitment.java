package com.github.AaronAA0721.villageragent.ai;

/**
 * "Finish what you set out to do" rule for the running goal.
 *
 * <p>Once a goal is selected the villager is <em>committed</em> to it: walking to the workstation, the field,
 * the forest or the mine never ends in a switch to some other ordinary task. Only emergencies, exhaustion and
 * curfew sleep (see {@link GoalScheduler#shouldPreempt(VillagerAgentData, AgentGoal, AgentGoal)}) may cut in.
 *
 * <p>Long multi-unit routines (farming, logging, mining) open a short <em>yield window</em> each time they finish
 * one unit of real work — one crop tended, one tree felled, one block mined. Inside that window, and only
 * there, the scheduler may pick something else. A {@code sequence} goal ignores those windows: its steps
 * run back-to-back and only emergencies, exhaustion and curfew sleep can cut in. A block that is being broken is never interrupted: the window
 * opens only after the break completed.
 */
public final class GoalCommitment {
    /** Longer than the 20-tick reconsider cadence, so every window is seen by at least one scheduling pass. */
    public static final int YIELD_WINDOW_TICKS = 30;

    private GoalCommitment() {}

    /** Reset when a new root goal takes the running slot. */
    public static void goalSelected(VillagerAgentData agent, long now) {
        BrainRuntime b = agent.brain();
        b.goalSelectedTick = now;
        b.workUnitsDone = 0;
        b.yieldWindowUntilTick = Long.MIN_VALUE;
    }

    /** A skill finished one unit of real work: between this unit and the next the goal may be swapped. */
    public static void workUnitDone(VillagerAgentData agent, long now) {
        BrainRuntime b = agent.brain();
        b.workUnitsDone++;
        b.yieldWindowUntilTick = now + YIELD_WINDOW_TICKS;
    }

    /** True while the running goal sits between two finished work units. */
    public static boolean atYieldPoint(VillagerAgentData agent, long now) {
        return agent.brain().workUnitsDone > 0 && now <= agent.brain().yieldWindowUntilTick;
    }

    /**
     * True when ordinary utility switching (and booked trade meetings) must leave {@code current} alone:
     * any real task is committed until it ends or reaches a yield point. Idle wandering is never committed.
     */
    public static boolean committed(VillagerAgentData agent, AgentGoal current, long now) {
        if (current == null) return false;
        if ("wander".equals(current.getGoalType()) && current.getPriority() < 9) return false;
        // A sequence is one uninterrupted plan: the yield windows its steps open do not apply to it.
        if (current.hasSteps()) return true;
        return !atYieldPoint(agent, now);
    }
}
