package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * "move" goal: walk to a concrete target position. Universal (no capability gate) — the LLM can
 * ask a villager to go anywhere; the shared walk state machine owns the actual navigation.
 */
public class MoveSkill implements VillagerSkill {
    @Override public boolean isPrimitive() { return true; }

    @Override public String id() { return "move"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "move".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        BlockPos t = goal.getTargetLocation();
        return VillagerAgentManager.driveMoveGoal(villager, agent, goal, t,
                "Arrived at " + (t != null ? t.getX() + "," + t.getZ() : "destination"));
    }
}
