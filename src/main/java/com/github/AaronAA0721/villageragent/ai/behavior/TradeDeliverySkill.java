package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/** Retires legacy persisted deliveries; new trades use paired meet_trade goals. */
public final class TradeDeliverySkill implements VillagerSkill {
    public String id() { return "deliver_trade"; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "deliver_trade".equals(g.getGoalType());
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld world) {
        return SkillResult.DELETE;
    }
}
