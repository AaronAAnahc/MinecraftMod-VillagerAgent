package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerTradeSystem;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * "trade" goal: walk to the nearest idle villager (or a specific one) and initiate an LLM-driven
 * barter via {@link VillagerTradeSystem}. The trade itself (propose → accept/reject/counter →
 * item exchange) is a separate system; this skill only owns the walk-to-partner phase.
 */
public class TradeSkill implements VillagerSkill {

    @Override public String id() { return "trade"; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.SOCIAL; }
    @Override public int minLevel() { return 2; } // "trade/courtship" — second social tier

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "trade".equals(goal.getGoalType()) && passesCapabilityGate(agent);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (com.github.AaronAA0721.villageragent.ai.TradeCoordinator.busy(agent.getVillagerId())) return SkillResult.WAITING;
        VillagerAction cur = agent.getCurrentAction();
        if (cur != null && !cur.isGoalDriven()) { agent.setCurrentAction(null); cur = null; }

        // Walking toward the partner (resolved once at start).
        if (cur != null && cur.getActionType() == VillagerAction.ActionType.MOVE && cur.isGoalDriven()) {
            BlockPos target = cur.getTargetBlockPos();
            if (target == null) { agent.setCurrentAction(null); return SkillResult.RUNNING; }
            if (villager.blockPosition().distSqr(target) <= VillagerAgentManager.GOAL_ARRIVE_SQ) {
                VillagerAgentData partner = VillagerAgentManager.resolveVillagerPartner(world, villager, agent, goal);
                if (partner != null) {
                    Entity e = world.getEntity(partner.getVillagerId());
                    if (e instanceof VillagerEntity) {
                        VillagerTradeSystem.initiate(world, villager, agent, (VillagerEntity) e, partner);
                    }
                } else {
                    agent.addMemory("Wanted to trade but no other villager is around");
                }
                goal.setCompleted(true);
                agent.setCurrentAction(null);
                villager.getNavigation().stop();
                return SkillResult.SUCCESS;
            }
            cur.incrementStuckTicks();
            if (cur.getStuckTicks() > VillagerAgentManager.GOAL_STUCK_TIMEOUT) {
                agent.addMemory("Couldn't reach the villager to trade");
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

        VillagerAgentData partner = VillagerAgentManager.resolveVillagerPartner(world, villager, agent, goal);
        if (partner == null) {
            agent.addMemory("Wanted to trade but couldn't find another villager");
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }
        Entity e = world.getEntity(partner.getVillagerId());
        if (!(e instanceof VillagerEntity)) { goal.setCompleted(true); return SkillResult.SUCCESS; }
        BlockPos target = ((VillagerEntity) e).blockPosition();
        VillagerAction move = new VillagerAction(VillagerAction.ActionType.MOVE,
                "Walking to trade with " + partner.getName());
        move.setTargetBlockPos(target);
        move.setPhase(VillagerAction.ActionPhase.WALKING);
        move.setGoalDriven(true);
        agent.setCurrentAction(move);
        villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, VillagerNavigationSupport.WALK_SPEED);
        return SkillResult.RUNNING;
    }
}
