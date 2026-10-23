package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/** One field interaction, usable both as a nested task and an explicit root request. */
public final class FarmActionSkill implements VillagerSkill {
    private final String type;
    public FarmActionSkill(String type) { this.type = type; }
    @Override public String id() { return type; }
    @Override public boolean isPrimitive() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.FARMING; }
    @Override public int minLevel() { return 1; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return type.equals(g.getGoalType()) && passesCapabilityGate(a);
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        BlockPos p = g.getTargetLocation();
        if (p == null) return SkillResult.DELETE;
        FarmSkill.Step step = "harvest".equals(type) ? FarmSkill.Step.HARVEST
                : "plant".equals(type) ? FarmSkill.Step.PLANT
                : "collect_crops".equals(type) ? FarmSkill.Step.COLLECT
                : FarmingAction.isRevertedFarmland(w, p) ? FarmSkill.Step.TILL_REVERTED : FarmSkill.Step.TILL_RECLAIM;
        if (step == FarmSkill.Step.TILL_RECLAIM && a.getCapabilityPool().levelOf(CapabilityId.FARMING) < 2) {
            g.setLastFailure(FailureReason.CAPABILITY_TOO_LOW.with(CapabilityId.FARMING, 2));
            return SkillResult.BLOCKED;
        }
        if ((step == FarmSkill.Step.PLANT && !FarmingAction.hasSeeds(a))
                || ((step == FarmSkill.Step.TILL_REVERTED || step == FarmSkill.Step.TILL_RECLAIM) && !FarmingAction.hasHoe(a))) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with(step == FarmSkill.Step.PLANT ? "minecraft:wheat_seeds" : "minecraft:iron_hoe"));
            return SkillResult.BLOCKED;
        }
        if (!FarmSkill.valid(step, p, a, w)) return SkillResult.SUCCESS; // already satisfied/changed
        BlockPos stand = step == FarmSkill.Step.PLANT || step == FarmSkill.Step.TILL_REVERTED
                || step == FarmSkill.Step.TILL_RECLAIM ? p.above() : p;
        int approach = FarmingAction.approachTarget(a, v, stand, true, 2, VillagerAgentManager.GOAL_STUCK_TIMEOUT, type);
        if (approach == 0) return SkillResult.RUNNING;
        if (approach == 2) {
            g.setLastFailure(FailureReason.UNREACHABLE.with(p.toString()));
            return SkillResult.FAIL;
        }
        return FarmSkill.perform(step, p, a, v, w) ? SkillResult.SUCCESS : SkillResult.FAIL;
    }
}
