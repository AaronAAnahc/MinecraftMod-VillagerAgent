package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.ActionLifecycle;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.LlmGoalIntake;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;

/**
 * "stroll" goal: walk around for a while. Used as a step of a group activity (an invitation to
 * "take a walk together"): the activity's leader (the host) picks nearby spots to walk to, and the
 * other participants follow the leader. Walking reuses the {@code move} skill as a child task.
 * It lasts until the activity's end time, or {@link #DEFAULT_LENGTH} when there is none; inside a
 * sequence the step is also capped by {@code SequenceSkill}'s step budget.
 *
 * <p>The start tick is kept on the goal ({@link AgentGoal#getStartedTick()}), so an interruption
 * does not restart the stroll.
 */
public final class StrollSkill implements VillagerSkill {
    private static final long DEFAULT_LENGTH = 1_200;   // 1 min real when the plan has no end time
    /** A follower stops walking once this close to the leader... */
    private static final double FOLLOW_SQ = 3.0 * 3.0;
    /** ...and only sets off again once the leader is farther than this (no re-pathing every step). */
    private static final double REJOIN_SQ = 6.0 * 6.0;
    private static final double LEADER_RANGE_SQ = 32.0 * 32.0;
    private static final int RANGE_XZ = 10, RANGE_Y = 4;
    /** Pause at each spot before picking the next one. */
    private static final long PAUSE_AT_SPOT = 40;

    private static final class State { AgentGoal walk; boolean following; long nextPick; }

    @Override public String id() { return "stroll"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "stroll".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        State s = agent.brain().tasks.current().state(State.class, State::new);
        if (goal.getStartedTick() < 0) goal.setStartedTick(now);
        long end = goal.hasEndTime() ? goal.getEndTick() : goal.getStartedTick() + DEFAULT_LENGTH;
        if (now >= end) {
            dropWalk(agent, villager, world, s);
            goal.setStartedTick(-1); // a repeatable stroll starts a fresh clock next time
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }
        goal.markProgress();
        agent.brain().goalStartedTick = now;

        VillagerEntity leader = leader(goal, agent, world, villager);
        if (leader != null) {
            if (!s.following) { dropWalk(agent, villager, world, s); s.following = true; }
            double d = villager.distanceToSqr(leader);
            if (d <= FOLLOW_SQ || (s.walk == null && d <= REJOIN_SQ)) {
                if (s.walk != null) dropWalk(agent, villager, world, s);
                villager.getLookControl().setLookAt(leader, 30f, 30f);
                return SkillResult.RUNNING;
            }
            return walk(agent, villager, world, s, leader.blockPosition(), goal, now);
        }
        if (s.following) { dropWalk(agent, villager, world, s); s.following = false; }
        if (s.walk == null) {
            if (now < s.nextPick) return SkillResult.RUNNING;
            Vector3d p = RandomPositionGenerator.getLandPos(villager, RANGE_XZ, RANGE_Y);
            if (p == null) { s.nextPick = now + PAUSE_AT_SPOT; return SkillResult.RUNNING; }
            return walk(agent, villager, world, s, new BlockPos(p), goal, now);
        }
        return walk(agent, villager, world, s, s.walk.getTargetLocation(), goal, now);
    }

    /** Walk toward {@code target} with a move child; a finished or failed walk clears it so a new spot is chosen. */
    private static SkillResult walk(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                    State s, BlockPos target, AgentGoal goal, long now) {
        if (s.walk != null) s.walk.setTargetLocation(target);
        SkillResult r = BehaviorExecutor.executeChild(agent, villager, world, () -> {
            AgentGoal w = new AgentGoal("move", "stroll", goal.getPriority());
            w.setSource(goal.getSource());
            w.setTargetLocation(target);
            s.walk = w;
            return w;
        });
        if (r != SkillResult.RUNNING) { s.walk = null; s.nextPick = now + PAUSE_AT_SPOT; }
        return SkillResult.RUNNING;
    }

    /**
     * The host of a shared activity when it is someone else, loaded, not too far and still taking part;
     * otherwise null (we lead). In an ask the requester does not take part, so there is no one to follow.
     */
    private static VillagerEntity leader(AgentGoal goal, VillagerAgentData agent, ServerWorld world, VillagerEntity self) {
        if (!goal.isShared() || goal.getLeaderId() == null || goal.getLeaderId().equals(agent.getVillagerId())) return null;
        VillagerAgentData host = VillagerAgentManager.getAgent(goal.getLeaderId());
        if (host == null || (goal.getGroupId() != null && !LlmGoalIntake.hasGroupGoal(host, goal.getGroupId()))) {
            return null; // the host has finished or dropped the activity: don't trail them to their next job
        }
        Entity e = world.getEntity(goal.getLeaderId());
        if (!(e instanceof VillagerEntity) || !e.isAlive() || self.distanceToSqr(e) > LEADER_RANGE_SQ) return null;
        return (VillagerEntity) e;
    }

    private static void dropWalk(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, State s) {
        if (agent.brain().tasks.current().child() != null) {
            ActionLifecycle.clearBody(agent, villager, world);
            ActionLifecycle.releaseTree(agent.brain().tasks.current().child(), agent, world);
            agent.brain().tasks.discardChild();
        }
        s.walk = null;
    }
}
