package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

public final class TradeMeetingSkill implements VillagerSkill {
    public String id() { return "meet_trade"; }
    public boolean isComposite() { return true; }
    public boolean canHandle(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        return id().equals(g.getGoalType()) && g.getTradeMessageId()!=null;
    }
    public SkillResult execute(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        return TradeCoordinator.advance(a,v,w,g);
    }
}
