package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.behavior.*;
import com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.server.ServerWorld;

/**
 * Compound task {@code supply_request(request)} — the provider side of cooperative supply.
 *
 * <pre>
 * supply_request(request)
 *   1. produce   — acquisition executor for the chosen output (craft / craft_inputs / extract / smelt;
 *                  request_supply excluded for this output), until shareable stock covers the whole request
 *   2. claim     — read the board (60 ticks); leave if the request is gone, expired or claimed by someone
 *                  else (produced items are kept); otherwise claim the whole request
 *   3. deliver   — the claim books a meeting: both villagers get a {@code meet_trade} goal, and this task ends
 * </pre>
 *
 * The task never reads the request remotely while producing; only board reads (here or in the routine
 * board check) reveal changes. Its outcome after delivery does not depend on what was received: the
 * requester's own acquisition re-evaluates its inventory.
 */
public final class CooperativeSupplySkill implements VillagerSkill {
    public String id() { return CooperativeSupply.RESPONSE_GOAL; }
    public boolean isComposite() { return true; }
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType()) && g.getTradeMessageId() != null && g.getTargetItem() != null;
    }
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        // 3. deliver: handed to the paired meet_trade goals once claimed; this task is done.
        if (g.getClaimId() != null) {
            BrainLog.diagnostic(a, "supply-done", "request=" + g.getTradeMessageId() + " session=" + g.getClaimId());
            return SkillResult.SUCCESS;
        }
        SupplyPolicy.Spec spec = SupplyPolicy.Spec.parse(g.getRequestSpec());
        Item item = ResourceCatalog.item(g.getTargetItem());
        if (spec == null || item == null || item == Items.AIR || !spec.matches(new ItemStack(item))) return SkillResult.DELETE;

        // 1. produce until shareable output covers the whole request read from the board.
        int needed = Math.max(1, g.getRequestUnits());
        int count = a.getInventory().countItem(new ItemStack(item));
        int shareable = SupplyPolicy.units(spec, CooperativeSupply.shareable(a, spec, g.getTradeMessageId()));
        if (shareable < needed) {
            if (count >= g.getTargetQuantity()) {
                // Part of the output is protected or committed; plan the missing share.
                g.setTargetQuantity(count + Math.max(1, SupplyPolicy.itemsToCover(spec, item, needed - shareable)));
            }
            SkillResult result = a.brain().acquisitions.execute(a, v, w, g, item, g.getTargetQuantity());
            if (result == SkillResult.SUCCESS) {
                g.setCompleted(false); // Production subtask done; the compound task continues with claiming.
                return SkillResult.RUNNING;
            }
            return result;
        }

        // 2. claim: read the board again before committing to a delivery.
        VillageNoticeBoards.Reading reading = a.brain().tasks.current().state(VillageNoticeBoards.Reading.class, VillageNoticeBoards.Reading::new);
        SkillResult read = VillageNoticeBoards.read(w, v, a, g, reading);
        if (read == SkillResult.RUNNING) return SkillResult.RUNNING;
        // An unreachable or missing board only delays the check.
        if (read != SkillResult.SUCCESS) return SkillResult.WAITING;
        VillageSignalBoard board = VillageMarkets.board(w, a.getVillagerId());
        if (board == null) return SkillResult.WAITING;
        // Reading refreshes board memory (and runs the usual check_board bookkeeping).
        VillagerEconomySystem.visitMarket(w, v, a);
        String reason = CooperativeSupply.invalidReason(a.brain().boardMemory.get(g.getTradeMessageId()), a.getVillagerId());
        if (reason != null) {
            BrainLog.diagnostic(a, "supply-cancel", "request=" + g.getTradeMessageId() + " reason=" + reason);
            return SkillResult.DELETE; // Produced items stay in inventory.
        }
        VillageSignalBoard.VillageMessage req = VillageSignalBoard.get().find(g.getTradeMessageId());
        if (CooperativeSupply.startDelivery(w, a, req)) {
            g.setClaimId(req.claim().id);
            g.markProgress();
            BrainLog.diagnostic(a, "supply-done", "request=" + g.getTradeMessageId() + " session=" + g.getClaimId()
                    + " handoff=meet_trade");
            return SkillResult.SUCCESS;
        }
        // Requester busy or unavailable (supply-claim-skip / trade-book-failed say why): try again in 30 s, not every 10 s.
        g.setMinimumExitCooldownTicks(600);
        return SkillResult.WAITING;
    }
}
