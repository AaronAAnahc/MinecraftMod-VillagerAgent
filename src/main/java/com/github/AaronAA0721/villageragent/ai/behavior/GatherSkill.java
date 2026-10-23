package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * "gather" goal: walk to a target location to collect items. Ground-item pickup is handled by
 * {@code ItemAttractionSystem} as the villager passes near drops, so this skill only owns the
 * navigation (via the shared {@link VillagerAgentManager#driveMoveGoal} walk state machine).
 */
public class GatherSkill implements VillagerSkill {

    @Override public String id() { return "gather"; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.GATHERING; }
    @Override public int minLevel() { return 1; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "gather".equals(goal.getGoalType()) && passesCapabilityGate(agent);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String item = goal.getTargetItem();
        if (item == null || com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(item) == net.minecraft.item.Items.AIR)
            return SkillResult.DELETE;
        int wanted = Math.max(1, goal.getTargetQuantity());
        if (com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.count(agent, item) >= wanted)
            return SkillResult.SUCCESS;
        com.github.AaronAA0721.villageragent.ai.work.ResourceWorkSystem.request(world, agent, item, wanted);
        BlockPos t = goal.getTargetLocation();
        if (t == null) return SkillResult.WAITING;
        SkillResult moved = VillagerAgentManager.driveMoveGoal(villager, agent, goal, t, "Visited gathering site");
        if (moved == SkillResult.SUCCESS) { goal.setCompleted(false); return SkillResult.WAITING; }
        return moved;
    }
}
