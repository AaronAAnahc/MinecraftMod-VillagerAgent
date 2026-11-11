package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.RoutineScheduler;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/**
 * "work" goal: one session of the villager's own profession work, chosen when it starts
 * ({@link RoutineScheduler#primaryWork}) and run as a child task of the ordinary routine skill (fish, smelt,
 * miner_work, ...). Resolving the job at run time keeps the goal meaningful wherever it was written, e.g. in a
 * neighbour's "ask" plan: it means the asked villager's job, not the asker's.
 */
public final class WorkSkill implements VillagerSkill {
    private static final class State { String job; }

    @Override public String id() { return "work"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "work".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        State s = agent.brain().tasks.current().state(State.class, State::new);
        if (s.job == null) {
            s.job = RoutineScheduler.primaryWork(agent, world);
            if (s.job == null) {
                agent.addMemory("Was asked to work but I have no trade to work at");
                return SkillResult.DELETE;
            }
        }
        final String job = s.job;
        SkillResult r = BehaviorExecutor.executeChild(agent, villager, world, () -> {
            AgentGoal g = new AgentGoal(job, job.replace('_', ' '), goal.getPriority());
            g.setSource(goal.getSource());
            return g;
        });
        if (r == SkillResult.SUCCESS) goal.setCompleted(true);
        return r;
    }
}
