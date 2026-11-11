package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.ActionLifecycle;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.GroupActivities;
import com.github.AaronAA0721.villageragent.ai.KnownVillagers;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * "sequence" goal: run its steps in order as child tasks of one root, so nothing else is scheduled
 * in between (see {@link com.github.AaronAA0721.villageragent.ai.GoalCommitment}). Each step is an
 * ordinary goal executed by its own skill through {@link BehaviorExecutor#executeChild}; the step
 * cursor lives on the goal, so an emergency interruption or a world reload resumes at the step that
 * was running.
 *
 * <p>A plain sequence ends with the first step that does not succeed. An <em>activity</em>
 * ({@link AgentGoal#isActivity()}: it has an end time or belongs to a group invitation) is about
 * being somewhere with someone, so it holds together instead:
 * <ul>
 *   <li><b>Gather</b> — in a group, after arriving at the meeting place (a {@code move}/{@code goto}
 *       step), wait until every other participant is within {@link #GATHER_RADIUS_SQ} or
 *       {@link #GATHER_TIMEOUT} has passed.</li>
 *   <li><b>Hold</b> — a step that is WAITING (e.g. no one to chat with yet) is retried after a short
 *       pause; a failing step is retried {@link #STEP_RETRIES} times and then skipped, rather than
 *       abandoning the activity.</li>
 *   <li><b>Linger</b> — with an end time, after the last step the villager stays at the meeting
 *       place with the others (looking at them, chatting now and then) until the end.</li>
 *   <li><b>End</b> — at the end time the activity finishes wherever it is.</li>
 * </ul>
 * Steps inherit the activity's group, leader and end so they can act on them (e.g. {@code socialize}
 * prefers participants, {@code stroll} follows the leader).
 */
public final class SequenceSkill implements VillagerSkill {
    private static final double GATHER_RADIUS_SQ = 8.0 * 8.0;
    private static final long GATHER_TIMEOUT = 1_200;        // 1 min real
    private static final double LINGER_RADIUS_SQ = 6.0 * 6.0;
    private static final long PAUSE_AFTER_WAITING = 40;
    private static final long PAUSE_AFTER_FAILURE = 60;
    private static final int STEP_RETRIES = 3;
    /** Without an end time, a step may only wait this many times before the activity gives up on it. */
    private static final int WAIT_RETRIES_WITHOUT_END = 10;
    private static final long CHAT_INTERVAL = 600;

    private static final class State {
        long pauseUntil;
        int failures;
        int waits;
        long gatherSince = -1;
        boolean gathered;
        BlockPos spot;
        long nextChat;
        AgentGoal walk;
    }

    @Override public String id() { return "sequence"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "sequence".equals(goal.getGoalType()) && goal.hasSteps();
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        long now = world.getGameTime();
        State s = agent.brain().tasks.current().state(State.class, State::new);
        if (goal.isPastEnd(now)) return finish(agent, goal, villager, world, "Time's up: ");

        AgentGoal step = goal.currentStep();
        if (step == null) {
            if (goal.hasEndTime()) return linger(agent, goal, villager, world, s, now);
            return finish(agent, goal, villager, world, "Finished my plan: ");
        }
        if (now < s.pauseUntil) { hold(agent, goal, villager, world, now); return SkillResult.RUNNING; }

        if (goal.getGroupId() != null && !s.gathered && arrivedAtMeetingPlace(goal) && !isLocationStep(step)) {
            if (!gather(agent, goal, villager, world, s, now)) return SkillResult.RUNNING;
        }

        inheritContext(goal, step);
        SkillResult r = BehaviorExecutor.executeChild(agent, villager, world, () -> step);
        if (r == SkillResult.RUNNING) return SkillResult.RUNNING;
        if (r == SkillResult.SUCCESS) {
            if (isLocationStep(step) && s.spot == null) s.spot = villager.blockPosition();
            return advance(agent, goal, villager, world, s, step, now, "done");
        }

        BrainLog.diagnostic(agent, "sequence-step", "step " + (goal.getStepIndex() + 1) + " "
                + step.getGoalType() + " ended " + r + " failure=" + step.getLastFailure());
        step.setCompleted(false); // some skills mark a failed goal completed; a retry must run this step again
        if (!goal.isActivity() || r == SkillResult.DELETE) {
            if (goal.getLastFailure() == null) goal.setLastFailure(step.getLastFailure());
            return r;
        }
        // Activity: hold together rather than abandon the plan.
        if (r == SkillResult.WAITING) {
            s.waits++;
            if (goal.hasEndTime() || s.waits <= WAIT_RETRIES_WITHOUT_END) {
                s.pauseUntil = now + PAUSE_AFTER_WAITING;
                return SkillResult.RUNNING;
            }
        } else if (++s.failures < STEP_RETRIES) {
            s.pauseUntil = now + PAUSE_AFTER_FAILURE;
            return SkillResult.RUNNING;
        }
        agent.addMemory("Couldn't " + step.getDescription() + "; carried on with the rest of the plan");
        return advance(agent, goal, villager, world, s, step, now, "skipped");
    }

    private static SkillResult advance(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager,
                                       ServerWorld world, State s, AgentGoal step, long now, String how) {
        BrainLog.diagnostic(agent, "sequence-step", how + " " + (goal.getStepIndex() + 1) + "/"
                + goal.getSteps().size() + " " + step.getGoalType());
        goal.setStepIndex(goal.getStepIndex() + 1);
        s.failures = 0;
        s.waits = 0;
        keepAlive(agent, goal, now); // each step gets a fresh watchdog budget
        if (goal.currentStep() == null && !goal.hasEndTime()) {
            return finish(agent, goal, villager, world, "Finished my plan: ");
        }
        return SkillResult.RUNNING;
    }

    /**
     * Wait at the meeting place for the other participants. Returns true when the group is together
     * (or the wait timed out) and the plan may go on.
     */
    private static boolean gather(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager,
                                  ServerWorld world, State s, long now) {
        List<VillagerEntity> others = GroupActivities.others(goal.getGroupId(), agent.getVillagerId(), world);
        List<String> missing = new ArrayList<>();
        for (VillagerEntity m : others) {
            if (villager.distanceToSqr(m) > GATHER_RADIUS_SQ) missing.add(KnownVillagers.nameOf(m.getUUID()));
        }
        if (s.gatherSince < 0) {
            s.gatherSince = now;
            if (s.spot == null) s.spot = villager.blockPosition();
            if (!missing.isEmpty()) agent.addMemory("Waiting for " + String.join(", ", missing) + " at the meeting place");
        }
        if (missing.isEmpty() || now - s.gatherSince >= GATHER_TIMEOUT || goal.isPastEnd(now)) {
            s.gathered = true;
            agent.addMemory(missing.isEmpty() ? "Everyone is here: " + goal.getDescription()
                    : String.join(", ", missing) + " didn't come; going ahead without them");
            BrainLog.diagnostic(agent, "group-gathered", "missing=" + missing + " waited=" + (now - s.gatherSince));
            return true;
        }
        hold(agent, goal, villager, world, now);
        return false;
    }

    /** After the last step of an activity with an end time: stay at the meeting place with the others. */
    private static SkillResult linger(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager,
                                      ServerWorld world, State s, long now) {
        if (s.spot == null) s.spot = meetingPlace(goal, villager);
        if (villager.blockPosition().distSqr(s.spot) > LINGER_RADIUS_SQ) {
            if (s.walk != null) s.walk.setTargetLocation(s.spot);
            SkillResult r = BehaviorExecutor.executeChild(agent, villager, world, () -> {
                AgentGoal walk = new AgentGoal("move", "go back to the meeting place", goal.getPriority());
                walk.setSource(goal.getSource());
                walk.setTargetLocation(s.spot);
                s.walk = walk;
                return walk;
            });
            if (r != SkillResult.RUNNING && r != SkillResult.SUCCESS) s.spot = villager.blockPosition(); // can't get back: stay here
            keepAlive(agent, goal, now);
            return SkillResult.RUNNING;
        }
        hold(agent, goal, villager, world, now);
        if (goal.getGroupId() != null && now >= s.nextChat && !agent.isSocializing()) {
            s.nextChat = now + CHAT_INTERVAL;
            VillagerEntity partner = GroupActivities.nearest(villager,
                    GroupActivities.others(goal.getGroupId(), agent.getVillagerId(), world));
            VillagerAgentData other = partner == null ? null : VillagerAgentManager.getAgent(partner.getUUID());
            if (other != null && !other.isSocializing() && villager.distanceToSqr(partner) <= LINGER_RADIUS_SQ) {
                SocializeSkill.chat(villager, agent, partner, other, world);
            }
        }
        return SkillResult.RUNNING;
    }

    /** Stand still facing the nearest participant; keeps the activity alive for the watchdog. */
    private static void hold(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world, long now) {
        if (agent.brain().tasks.current().child() == null) villager.getNavigation().stop();
        VillagerEntity m = GroupActivities.nearest(villager,
                GroupActivities.others(goal.getGroupId(), agent.getVillagerId(), world));
        if (m != null) villager.getLookControl().setLookAt(m, 30f, 30f);
        keepAlive(agent, goal, now);
    }

    private static SkillResult finish(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager,
                                      ServerWorld world, String memory) {
        if (agent.brain().tasks.current().child() != null) {
            ActionLifecycle.clearBody(agent, villager, world);
            ActionLifecycle.releaseTree(agent.brain().tasks.current().child(), agent, world);
            agent.brain().tasks.discardChild();
        }
        agent.addMemory(memory + goal.getDescription());
        goal.setCompleted(true);
        return SkillResult.SUCCESS;
    }

    private static void keepAlive(VillagerAgentData agent, AgentGoal goal, long now) {
        goal.markProgress();
        agent.brain().goalStartedTick = now;
    }

    private static void inheritContext(AgentGoal goal, AgentGoal step) {
        step.setGroupId(goal.getGroupId());
        step.setLeaderId(goal.getLeaderId());
        step.setShared(goal.isShared());
        step.setEndTick(goal.getEndTick());
    }

    private static boolean isLocationStep(AgentGoal step) {
        return "move".equals(step.getGoalType()) || "goto".equals(step.getGoalType());
    }

    /** True once at least one walk to a place has been done and the next step is not another walk. */
    private static boolean arrivedAtMeetingPlace(AgentGoal goal) {
        int i = goal.getStepIndex();
        return i > 0 && isLocationStep(goal.getSteps().get(i - 1));
    }

    private static BlockPos meetingPlace(AgentGoal goal, VillagerEntity villager) {
        for (AgentGoal st : goal.getSteps()) if (st.getTargetLocation() != null) return st.getTargetLocation();
        return villager.blockPosition();
    }
}
