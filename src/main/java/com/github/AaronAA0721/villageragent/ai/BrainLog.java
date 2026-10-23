package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

import java.util.StringJoiner;

/**
 * Centralised, toggle-able trace of a villager's "decision brain" — the lifecycle of every
 * {@link AgentGoal} (todo) as it enters, changes state, and leaves the unified goal list.
 *
 * <p>Purpose is pure debugging: with {@code brain_log_enabled=true} the log shows, per villager,
 * <em>why</em> a todo appeared, when/why it ended, when the running goal switched, and each
 * suspend/resume transition. All messages go to the dedicated {@code VillagerBrain} logger so
 * they can be filtered separately from the rest of the game log.
 *
 * <p>Every method is a no-op unless {@link ModConfig#BRAIN_LOG_ENABLED} is true, and never throws,
 * so instrumentation can be scattered freely through the decision pipeline without any risk to
 * gameplay.
 */
public final class BrainLog {

    private static final Logger LOGGER = LogManager.getLogger("VillagerBrain");

    private BrainLog() {}

    private static boolean on() {
        // Defend against config-key drift (null value before the toml is materialised).
        return ModConfig.BRAIN_LOG_ENABLED != null && ModConfig.BRAIN_LOG_ENABLED.get();
    }

    /** A new todo was enqueued into the goal list. {@code source} says where it came from. */
    public static void goalAdded(VillagerAgentData agent, AgentGoal g, String source) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | +TODO | {} | \"{}\" | prio={} imp={} {} w={} | src={}",
                agent.getName(), agent.getVillagerId(), g.getGoalType(), g.getDescription(), g.getPriority(),
                g.getImportance(), g.getLifecycle(), g.getWeight(), source);
    }

    /** A todo was <em>not</em> added because the goal list was full (MAX_GOALS cap). */
    public static void goalSkipped(VillagerAgentData agent, AgentGoal g, String source) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | +TODO(ignored, cap full) | {} | \"{}\" | src={}",
                agent.getName(), agent.getVillagerId(), g.getGoalType(), g.getDescription(), source);
    }

    /** A todo left the goal list — completed, expired, pruned, or given up on. */
    public static void goalEnded(VillagerAgentData agent, AgentGoal g, String reason) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | -TODO | {} | \"{}\" | reason={}",
                agent.getName(), agent.getVillagerId(), g.getGoalType(), g.getDescription(), reason);
    }

    /** The running goal changed (picked for the first time, preempted, or cleared). */
    public static void goalSwitch(VillagerAgentData agent, AgentGoal from, AgentGoal to, String reason) {
        if (!on() || agent == null) return;
        LOGGER.info("{} [{}] | SWITCH | {} -> {} | reason={}",
                agent.getName(), agent.getVillagerId(), brief(from), brief(to), reason);
    }

    /** A todo's own scheduling state flipped (suspended / resumed). */
    public static void goalState(VillagerAgentData agent, AgentGoal g, String state, String reason) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | STATE | {} | {} | \"{}\" | reason={}",
                agent.getName(), agent.getVillagerId(), state, g.getGoalType(), g.getDescription(), reason);
    }

    /** A skill claimed the running goal and ran one tick — logs which handler and its result. */
    public static void skillDispatch(VillagerAgentData agent, AgentGoal g, String skillId, SkillResult result) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | SKILL | {} | handler={} -> {}",
                agent.getName(), agent.getVillagerId(), g.getGoalType(), skillId, result);
    }

    /** A goal was selected by the scheduler. */
    public static void decide(VillagerAgentData agent, String detail) {
        if (!on() || agent == null) return;
        LOGGER.info("{} [{}] | DECIDE | {}", agent.getName(), agent.getVillagerId(), detail);
    }

    /** Bounded snapshot of both the displayed activity and the actual execution state. */
    public static void snapshot(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, String stage) {
        if (!on() || agent == null || villager == null) return;
        long now = world.getGameTime();
        if (agent.brain().lastDiagnosticTick != Long.MIN_VALUE
                && now - agent.brain().lastDiagnosticTick < 200) return;
        agent.brain().lastDiagnosticTick = now;
        StringJoiner goals = new StringJoiner(",", "[", "]");
        for (AgentGoal goal : agent.getGoals()) {
            double utility = GoalScheduler.utility(agent, goal, now);
            goals.add(goal.getGoalType() + ":" + String.format(java.util.Locale.ROOT, "%.2f", utility)
                    + (goal.isOnCooldown(now) ? "@cooldown=" + (goal.getCooldownUntilTick() - now) : "")
                    + (goal.isSuspended() ? "@suspended" : "")
                    + (goal.isCompleted() ? "@completed" : ""));
        }
        LOGGER.info("{} [{}] | SNAPSHOT | stage={} tick={} dimension={} pos={} activity={} running={} action={} "
                        + "navDone={} sleeping={} hunger={} fatigue={} role={} farmOpportunities={} socialPartner={} goals={}",
                agent.getName(), agent.getVillagerId(), stage, now, world.dimension().location(),
                villager.blockPosition(), agent.getCurrentActivity(), brief(agent.getRunningGoal()),
                agent.getCurrentAction(), villager.getNavigation().isDone(), villager.isSleeping(),
                agent.getHunger(), agent.getFatigue(), agent.getResourceProfession(),
                agent.brain().farmOpportunities, agent.brain().socialPartner, goals);
    }

    /** A reason for not making progress. Call at transitions or with an explicit cadence. */
    public static void diagnostic(VillagerAgentData agent, String stage, String detail) {
        if (!on() || agent == null) return;
        LOGGER.info("{} [{}] | DIAG | {} | {}", agent.getName(), agent.getVillagerId(), stage, detail);
    }

    /**
     * The running goal was claimed by NO skill. This is the single highest-value line for
     * diagnosing "my villager stands there doing nothing": an unclaimed goal used to sit on the
     * running slot indefinitely (the executor only debug-logged it and never counted a blocked
     * streak), which silently froze the whole goal queue for the rest of the day.
     *
     * @param detail the per-skill scan (handle / required capability / current level)
     */
    public static void noHandler(VillagerAgentData agent, AgentGoal g, String detail) {
        if (!on() || agent == null || g == null) return;
        LOGGER.info("{} [{}] | NOHANDLER | {} | \"{}\" | prio={} {} | scan={}",
                agent.getName(), agent.getVillagerId(), g.getGoalType(), g.getDescription(), g.getPriority(),
                g.getLifecycle(), detail);
    }

    /** The villager's current action changed (a new non-null {@link VillagerAction} was set). */
    public static void actionChange(VillagerAgentData agent, VillagerAction action) {
        if (!on() || agent == null || action == null) return;
        LOGGER.info("{} [{}] | ACTION | {} | \"{}\" | phase={} | goalDriven={}",
                agent.getName(), agent.getVillagerId(), action.getActionType(), action.getDescription(),
                action.getPhase(), action.isGoalDriven());
    }

    /** A capability levelled up. Only logs the transition, not every XP tick (keeps volume low). */
    public static void levelUp(VillagerAgentData agent, CapabilityId cap, int before, int after, int xpGained) {
        if (!on() || agent == null || cap == null || after <= before) return;
        LOGGER.info("{} [{}] | LEVELUP | {} | {} -> {} (+{}xp)",
                agent.getName(), agent.getVillagerId(), cap, before, after, xpGained);
    }

    private static String brief(AgentGoal g) {
        return g == null ? "none" : g.getGoalType() + "(\"" + g.getDescription() + "\")";
    }
}
