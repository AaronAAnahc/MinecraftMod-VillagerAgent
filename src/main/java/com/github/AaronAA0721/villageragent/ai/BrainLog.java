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

    /** Bounded collision snapshot; does not request movement or change path evaluation. */
    public static void movementObstacle(VillagerAgentData agent, VillagerEntity v, ServerWorld world) {
        if (!on() || !v.horizontalCollision) return;
        BrainRuntime b = agent.brain();
        long now = world.getGameTime();
        if (b.lastMovementDiagnosticTick != Long.MIN_VALUE && now - b.lastMovementDiagnosticTick < 20) return;
        b.lastMovementDiagnosticTick = now;
        net.minecraft.pathfinding.Path path = v.getNavigation().getPath();
        net.minecraft.util.math.BlockPos next = path == null || path.isDone() ? null : path.getNodePos(path.getNextNodeIndex());
        StringJoiner blocks = new StringJoiner(";", "[", "]");
        net.minecraft.util.math.BlockPos feet = v.blockPosition();
        // Bounded 3x3x3 neighborhood, including thin and vertically mounted trapdoors.
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            net.minecraft.util.math.BlockPos pos = feet.offset(x, y, z);
            if (!world.isLoaded(pos)) continue;
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            net.minecraft.util.math.shapes.VoxelShape shape = state.getCollisionShape(world, pos);
            if (!shape.isEmpty()) blocks.add(pos + "=" + state + " shape=" + shape.toAabbs());
        }
        diagnostic(agent, "[DEBUG-movement]", "tick=" + now + " dimension=" + world.dimension().location()
                + " pos=" + v.position() + " velocity=" + v.getDeltaMovement()
                + " water=" + v.isInWater() + " onGround=" + v.isOnGround()
                + " horizontalCollision=" + v.horizontalCollision
                + " headroom1=" + world.noCollision(v, v.getBoundingBox().move(0, 1, 0))
                + " navDone=" + v.getNavigation().isDone() + " next=" + next
                + " rise=" + (next == null ? "none" : next.getY() - v.getY())
                + " target=" + (path == null ? "none" : path.getTarget())
                + " reachable=" + (path != null && path.canReach())
                + " action=" + agent.getCurrentAction() + " collisions=" + blocks);
    }

    /** Sample after SwimTask, before travel: velocity is not the result of this tick's jump. */
    public static void swimming(VillagerAgentData agent, VillagerEntity v, ServerWorld world) {
        BrainRuntime b = agent.brain();
        if (!on()) {
            b.lastSwimDiagnosticPosition = null;
            b.lastSwimDiagnosticTick = Long.MIN_VALUE;
            return;
        }
        boolean wet = v.isInWater();
        if (!wet && b.lastSwimDiagnosticPosition == null) return;
        long now = world.getGameTime();
        if (wet && b.lastSwimDiagnosticTick != Long.MIN_VALUE
                && now - b.lastSwimDiagnosticTick < 20) return;
        net.minecraft.pathfinding.Path path = v.getNavigation().getPath();
        net.minecraft.util.math.BlockPos next = path == null || path.isDone() ? null
                : path.getNodePos(path.getNextNodeIndex());
        net.minecraft.util.math.BlockPos feet = v.blockPosition();
        net.minecraft.fluid.FluidState fluid = world.getFluidState(feet);
        diagnostic(agent, "[DEBUG-swim]", "tick=" + now + " dimension=" + world.dimension().location()
                + " phase=after-swim-before-travel water=" + wet + " pos=" + v.position()
                + " sampleTicks=" + (b.lastSwimDiagnosticPosition == null ? 0 : now - b.lastSwimDiagnosticTick)
                + " displacement=" + (b.lastSwimDiagnosticPosition == null ? "first" : v.position().subtract(b.lastSwimDiagnosticPosition))
                + " velocity=" + v.getDeltaMovement()
                + " waterDepth=" + v.getFluidHeight(net.minecraft.tags.FluidTags.WATER)
                + " swim=" + b.swim.getStatus() + " onGround=" + v.isOnGround()
                + " horizontalCollision=" + v.horizontalCollision
                + " headroom1=" + world.noCollision(v, v.getBoundingBox().move(0, 1, 0))
                + " feetBlock=" + world.getBlockState(feet) + " feetFluid=" + fluid
                + " feetFlow=" + fluid.getFlow(world, feet)
                + " navDone=" + v.getNavigation().isDone() + " next=" + next
                + " rise=" + (next == null ? "none" : next.getY() - v.getY())
                + " target=" + (path == null ? "none" : path.getTarget())
                + " reachable=" + (path != null && path.canReach())
                + " action=" + agent.getCurrentAction());
        b.lastSwimDiagnosticTick = now;
        b.lastSwimDiagnosticPosition = wet ? v.position() : null;
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
