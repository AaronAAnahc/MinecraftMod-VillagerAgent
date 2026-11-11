package com.github.AaronAA0721.villageragent.ai;
import com.github.AaronAA0721.villageragent.ai.behavior.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
/** Retires legacy exclusive {@code produce_order} goals; cooperative responses use {@code supply_request}. */
public final class PaidProductionSkill implements VillagerSkill {
    public String id() { return "produce_order"; }
    public boolean canHandle(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) { return id().equals(g.getGoalType()); }
    public SkillResult execute(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) { return SkillResult.DELETE; }
}
