package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * "converse" goal: walk to a specific player/villager (or the nearest one matching a profession)
 * and "talk". On arrival it also reconciles any debt the target player owes the villager
 * (see {@link VillagerAgentManager#onConverseArrive}).
 */
public class ConverseSkill implements VillagerSkill {

    @Override public String id() { return "converse"; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.SOCIAL; }
    @Override public int minLevel() { return 1; } // "basic conversation"

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "converse".equals(goal.getGoalType()) && passesCapabilityGate(agent);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        BlockPos target = VillagerAgentManager.resolveConverseTarget(world, villager, agent, goal);
        if (target == null) {
            agent.addMemory("Wanted to talk to someone but couldn't find them");
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && !cur.isGoalDriven()) { agent.setCurrentAction(null); cur = null; }

        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
            if (villager.blockPosition().distSqr(target) <= VillagerAgentManager.GOAL_ARRIVE_SQ) {
                VillagerAgentManager.onConverseArrive(villager, agent, goal);
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                return SkillResult.SUCCESS;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > VillagerAgentManager.GOAL_STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach the person I wanted to talk to");
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                goal.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                return SkillResult.FAIL;
            }
            if (cur.getStuckTicks() % 40 == 0) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
            }
            return SkillResult.RUNNING;
        }

        if (cur != null) return SkillResult.RUNNING;

        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE, "Walking to talk to someone");
        move.setTargetBlockPos(target);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        move.setGoalDriven(true);
        agent.setCurrentAction(move);
        villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
        return SkillResult.RUNNING;
    }
}
