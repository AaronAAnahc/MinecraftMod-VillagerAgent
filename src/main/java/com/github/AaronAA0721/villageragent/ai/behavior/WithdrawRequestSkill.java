package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/**
 * Walk to the village notice board and take down this villager's open requests whose need was met some
 * other way (see {@link OwnRequests}). Requests are never withdrawn remotely.
 */
public final class WithdrawRequestSkill implements VillagerSkill {
    public String id() { return OwnRequests.WITHDRAW_GOAL; }
    public boolean isComposite() { return true; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType()) && ModConfig.ENABLE_ECONOMY.get() && VillageNoticeBoards.position(w, v) != null;
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        VillageNoticeBoards.Reading reading = a.brain().tasks.current().state(VillageNoticeBoards.Reading.class, VillageNoticeBoards.Reading::new);
        SkillResult result = VillageNoticeBoards.read(w, v, a, g, reading);
        if (result != SkillResult.SUCCESS) return result;
        int removed = OwnRequests.withdrawUnneeded(a, v, w);
        BrainLog.diagnostic(a, "request-withdraw-visit", "removed=" + removed);
        // Being at the board anyway, read it like a routine check.
        VillagerEconomySystem.visitMarket(w, v, a);
        return SkillResult.SUCCESS;
    }
}
