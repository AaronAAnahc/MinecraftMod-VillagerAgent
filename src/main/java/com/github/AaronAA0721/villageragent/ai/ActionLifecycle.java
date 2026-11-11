package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/** One cancellation boundary for navigation, block progress and task reservations. */
public final class ActionLifecycle {
    public static void cancel(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        com.github.AaronAA0721.villageragent.ai.behavior.FindJobSiteSkill.releaseCandidate(villager);
        clearBody(agent, villager, world);
        releaseTree(agent.brain().tasks.root(), agent, world);
        ResourceWorkSystem.release(world, agent, agent.getRunningGoal());
        agent.setFarmTicks(0);
        agent.brain().tasks.reset();
        agent.setCurrentActivity("idle");
    }
    public static void releaseTree(TaskExecution.Frame frame, VillagerAgentData agent, ServerWorld world) {
        if (frame == null) return;
        releaseTree(frame.child(), agent, world);
        ResourceWorkSystem.release(world, agent, frame.goal);
    }
    public static void clearBody(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        com.github.AaronAA0721.villageragent.ai.work.MiningWorldData.get(world).release(villager.getUUID());
        VillagerAction action = agent.getCurrentAction();
        if (action != null && action.getTargetBlockPos() != null && action.getActionType() == VillagerAction.ActionType.BREAK)
            world.destroyBlockProgress(villager.getId(), action.getTargetBlockPos(), -1);
        agent.setCurrentAction(null);
        villager.getNavigation().stop();
        if (villager.isSleeping()) villager.stopSleeping();
    }
    /**
     * Drop whatever the villager is doing and run {@code goal} now (operator commands and showcase scenes).
     * The goal is added to the brain if it is not there yet; the scheduler may still preempt it later as usual.
     */
    public static void startNow(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, AgentGoal goal) {
        cancel(agent, villager, world);
        if (!agent.getGoals().contains(goal)) agent.getGoals().add(goal);
        agent.setRunningGoal(goal);
        agent.brain().goalStartedTick = world.getGameTime();
        agent.setCurrentActivity(goal.getGoalType());
    }
    private ActionLifecycle() {}
}
