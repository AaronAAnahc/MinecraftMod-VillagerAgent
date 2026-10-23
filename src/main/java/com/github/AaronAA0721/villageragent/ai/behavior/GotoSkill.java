package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * "goto" goal: navigate to the nearest building matching the goal's type filter (house / cave_house /
 * any). The LLM only says "go to a house"; this resolves it to a concrete position by querying the
 * shared {@link WorldStructureIndex} (no world scan) and walking to its seed bed.
 */
public class GotoSkill implements VillagerSkill {

    @Override public String id() { return "goto"; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "goto".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        String want = goal.getTargetBuildingType();
        BlockPos here = villager.blockPosition();

        BuildingRecord best = null;
        double bestDist = Double.MAX_VALUE;
        for (BuildingRecord r : WorldStructureIndex.instance(world).queryNear(here, 8)) {
            if (!VillagerAgentManager.matchesBuildingType(r.coarseType, want)) continue;
            double d = here.distSqr(r.seedBed);
            if (d < bestDist) { bestDist = d; best = r; }
        }

        if (best == null) {
            agent.addMemory("Wanted to go to " + (want == null ? "a building" : "a " + want)
                    + " but couldn't find one nearby");
            goal.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        return VillagerAgentManager.driveMoveGoal(villager, agent, goal, best.seedBed,
                "Arrived at the " + best.coarseType + " building near "
                        + best.seedBed.getX() + "," + best.seedBed.getZ());
    }
}
