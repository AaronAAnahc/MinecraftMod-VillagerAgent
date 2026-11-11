package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

public final class TradeMeetingSkill implements VillagerSkill {
    public String id() { return "meet_trade"; }
    public boolean isComposite() { return true; }

    /**
     * Reduce chance after repeated failures (UNREACHABLE). Each recorded failure halves
     * the weight so the villager stops monopolizing ticks walking to an unreachable partner.
     */
    public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        int failures = g.getFailureCount();
        if (failures >= 2) return 0; // Give up after 2 UNREACHABLE attempts — the goal will be pruned by GoalExitPolicy.
        return failures == 0 ? 2.5 : 1.0;
    }
    public boolean canHandle(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        return id().equals(g.getGoalType()) && g.getTradeMessageId()!=null;
    }
    public SkillResult execute(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        return TradeCoordinator.advance(a,v,w,g);
    }
}
