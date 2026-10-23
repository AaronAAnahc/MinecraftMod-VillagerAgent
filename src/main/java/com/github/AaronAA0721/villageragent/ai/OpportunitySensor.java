package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/** Local facts for utility scoring; sampled on perception cadence. */
public final class OpportunitySensor {
    public static void refresh(VillagerAgentData agent, VillagerEntity self, ServerWorld world) {
        BrainRuntime b = agent.brain(); b.socialPartner = null;
        double best = 144;
        for (VillagerEntity other : world.getEntitiesOfClass(VillagerEntity.class, self.getBoundingBox().inflate(12))) {
            if (other == self || other.isSleeping() || other.isBaby()) continue;
            VillagerAgentData data = VillagerAgentManager.getAgent(other.getUUID());
            // Match SocializeSkill's live partner predicate, so a busy neighbour does not
            // produce a positive social goal that immediately returns WAITING.
            if (data == null || data.isSocializing() || data.getCurrentAction() != null
                    || world.getGameTime() - data.getLastSocialTick() < 400) continue;
            double d = self.distanceToSqr(other);
            if (d < best) { best = d; b.socialPartner = other.getUUID(); }
        }
        b.farmOpportunities = 0;
        if (agent.getCapabilityPool().levelOf(com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId.FARMING) > 0) {
            FarmMemory.refresh(agent, self, world);
            BlockPos p = self.blockPosition();
            b.farmOpportunities = FarmingAction.findMatureCropsSorted(world, p).size();
            b.farmOpportunities += FarmingAction.findEmptyFarmlandSorted(world, p).size();
            b.farmOpportunities += FarmingAction.findRevertedFarmlandSorted(world, p).size();
            // Missing tools/seeds must reach FarmSkill so it can request supplies. Reclaiming
            // is also real work, even when the village has no existing crop plots yet.
            if (agent.getCapabilityPool().levelOf(com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId.FARMING) >= 2
                    && FarmingAction.findNearestCultivatableDirt(world, p,
                        com.github.AaronAA0721.villageragent.ai.behavior.FarmSkill.PATCH_RADIUS) != null)
                b.farmOpportunities++;
        }
    }
    private OpportunitySensor() {}
}
