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
        VillagerAction action = agent.getCurrentAction();
        if (action != null && action.getTargetBlockPos() != null && action.getActionType() == VillagerAction.ActionType.BREAK)
            world.destroyBlockProgress(villager.getId(), action.getTargetBlockPos(), -1);
        agent.setCurrentAction(null);
        villager.getNavigation().stop();
        if (villager.isSleeping()) villager.stopSleeping();
    }
    private ActionLifecycle() {}
}
