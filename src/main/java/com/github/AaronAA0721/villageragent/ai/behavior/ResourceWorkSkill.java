package com.github.AaronAA0721.villageragent.ai.behavior;
import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
/** Compatibility tombstone: saved free-delivery goals cannot transfer inventory. */
public final class ResourceWorkSkill implements VillagerSkill {
    public String id() { return "fulfill_order"; }
    public boolean canHandle(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) { return false; }
    public SkillResult execute(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) { return SkillResult.DELETE; }
}
