package com.github.AaronAA0721.villageragent.ai.behavior;
import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
/**
 * Routine notice-board check: stand at the board for a few seconds, then advertise shareable stock and
 * read requests — adding responses, dropping
 * responses whose request vanished or was claimed, and claiming requests it can fill from stock.
 */
public final class TradeSkill implements VillagerSkill {
    public String id() { return "trade"; }
    public boolean canHandle(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        return id().equals(g.getGoalType()) && ModConfig.ENABLE_ECONOMY.get()
                && VillageNoticeBoards.position(w,v) != null;
    }
    public SkillResult execute(VillagerAgentData a,AgentGoal g,VillagerEntity v,ServerWorld w) {
        // Stand at the board for a few seconds; what was read only takes effect after the dwell.
        VillageNoticeBoards.Reading reading = a.brain().tasks.current().state(VillageNoticeBoards.Reading.class, VillageNoticeBoards.Reading::new);
        SkillResult result = VillageNoticeBoards.read(w,v,a,g,reading);
        if (result != SkillResult.SUCCESS) return result;
        VillagerEconomySystem.visitMarket(w,v,a);
        return SkillResult.SUCCESS;
    }
}
