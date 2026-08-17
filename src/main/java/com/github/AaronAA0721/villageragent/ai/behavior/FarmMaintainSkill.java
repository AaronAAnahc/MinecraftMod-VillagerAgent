package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.FarmingAction;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.List;

/**
 * Maintenance skill: keep existing farmland intact by re-ploughing "reverted" dirt.
 *
 * <p>A dirt block with >=3 farmland neighbours is treated as an old farmland that got trampled
 * back to dirt — exactly the block this skill hunts. Each tick it finds the nearest such block,
 * walks to it (a {@code farmMove} MOVE action), and ploughs it. When no reverted blocks remain
 * it completes the goal; the routine-goal generator in {@code VillagerAgentManager} will re-issue
 * it on the next farming tick if more appear.
 *
 * <p>Trigger: a {@code farm} goal whose {@code farmMode} is {@code "maintain"}. It also claims a
 * plain {@code farm} goal (no mode) when reverted farmland is actually nearby, so the skill runs
 * opportunistically even if the schedule didn't specify a mode.
 */
public class FarmMaintainSkill implements VillagerSkill {
    private static final double ARRIVE_SQ = 2.0;   // ~1 block away
    private static final int STUCK_TIMEOUT = 200;   // ~10 s at 20 tps

    @Override public String id() { return "farm_maintain"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!"farm".equals(goal.getGoalType())) return false;
        String mode = goal.getFarmMode();
        if ("maintain".equals(mode)) return true;
        // Opportunistic: a plain farm goal maintains when reverted farmland actually exists.
        return mode == null
                && FarmingAction.hasRevertedFarmlandNearby(world, villager.blockPosition(), FarmingAction.SCAN_RADIUS);
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        if (!FarmingAction.hasHoe(agent)) {
            goal.setCompleted(true);
            return SkillResult.SUCCESS; // nothing this villager can do without a hoe
        }

        List<BlockPos> targets = FarmingAction.findRevertedFarmlandSorted(world, villager.blockPosition());
        if (targets.isEmpty()) {
            goal.setCompleted(true);
            return SkillResult.SUCCESS; // nothing to maintain right now
        }

        BlockPos target = targets.get(0);
        int arrive = FarmingAction.approachTarget(agent, villager, target, true, ARRIVE_SQ, STUCK_TIMEOUT,
                "Maintaining reverted farmland");
        if (arrive == 1) {
            FarmingAction.tillBlockAt(villager, world, agent, target);
            // Loop: next tick re-scans and finds the next reverted block (or finishes).
            return SkillResult.RUNNING;
        }
        // arrive == 0 (walking) or 2 (blocked) — keep the goal alive and retry.
        return SkillResult.RUNNING;
    }
}
