package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.VillagerNavigationSupport;
import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;

/** Existing economy trades use the same navigation owner as every other task. */
public final class TradeDeliverySkill implements VillagerSkill {
    public String id() { return "deliver_trade"; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "deliver_trade".equals(g.getGoalType()) && g.getTradeMessageId() != null;
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld world) {
        VillageSignalBoard.VillageMessage want = VillageSignalBoard.get().find(g.getTradeMessageId());
        if (want == null || want.resolved || want.matchedWith == null) return SkillResult.DELETE;
        VillageSignalBoard.VillageMessage have = VillageSignalBoard.get().find(want.matchedWith);
        if (have == null || !have.author.equals(a.getVillagerId())) return SkillResult.DELETE;
        Entity customer = world.getEntity(want.author);
        if (!(customer instanceof VillagerEntity) || !customer.isAlive()) return SkillResult.WAITING;
        if (v.distanceToSqr(customer) <= 9) {
            return VillagerEconomySystem.completeMatchedExchange(world, want.id) > 0 ? SkillResult.SUCCESS : SkillResult.WAITING;
        }
        VillagerAction move = a.getCurrentAction();
        if (move == null) {
            move = new VillagerAction(VillagerAction.ActionType.MOVE, "Deliver trade to neighbour");
            move.setGoalDriven(true); a.setCurrentAction(move);
            v.getNavigation().moveTo(customer, VillagerNavigationSupport.WALK_SPEED);
        }
        move.setTargetBlockPos(customer.blockPosition());
        move.incrementStuckTicks();
        if (move.getStuckTicks() > 600) {
            want.matchedWith = null; have.matchedWith = null;
            return SkillResult.FAIL;
        }
        if (move.getStuckTicks() % 20 == 0) v.getNavigation().moveTo(customer, VillagerNavigationSupport.WALK_SPEED);
        return SkillResult.RUNNING;
    }
}
