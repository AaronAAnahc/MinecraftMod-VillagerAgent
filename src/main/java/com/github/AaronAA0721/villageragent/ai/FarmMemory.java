package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import net.minecraft.block.Blocks;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/** Remember observed fields; inspect live crops only after travelling back to them. */
public final class FarmMemory {
    public static void refresh(VillagerAgentData agent, VillagerEntity self, ServerWorld world) {
        long now = world.getGameTime();
        agent.brain().farmRevisitAfter.entrySet().removeIf(e -> e.getValue() <= now);
        if (now - agent.brain().lastFarmMemoryScan >= 200) {
            agent.brain().lastFarmMemoryScan = now;
            BlockPos here = self.blockPosition();
            // Includes immature crops: a field remains worth remembering between harvests.
            for (BlockPos p : BlockPos.betweenClosed(here.offset(-12, -3, -12), here.offset(12, 3, 12))) {
                if (!world.isLoaded(p) || world.getBlockState(p).getBlock() != Blocks.FARMLAND) continue;
                ChunkMemory memory = agent.getOrCreateChunkMemory(p.getX() >> 4, p.getZ() >> 4);
                if (!memory.has(ChunkFeature.FARMLAND)) {
                    memory.recordFeature(ChunkFeature.FARMLAND, p.getX() & 15, p.getY(), p.getZ() & 15);
                    BrainLog.diagnostic(agent, "farm-memory-add", "anchor=" + p);
                }
            }
            agent.brain().knownFarmland = knownFarmland(agent, world);
        }
        if (bootstrapping(agent)) {
            if (now - agent.brain().lastBootstrapScan >= 1200 || (agent.brain().bootstrapSite != null
                    && !FarmingAction.isTillableDirt(world, agent.brain().bootstrapSite)
                    && !FarmingAction.isFarmland(world, agent.brain().bootstrapSite))) {
                agent.brain().lastBootstrapScan = now;
                BlockPos site = findBootstrapSite(agent, self, world);
                if (site != null && !site.equals(agent.brain().bootstrapSite))
                    BrainLog.diagnostic(agent, "farm-bootstrap-site", "site=" + site + " knownFarmland=" + agent.brain().knownFarmland);
                agent.brain().bootstrapSite = site;
            }
        } else agent.brain().bootstrapSite = null;
        BlockPos nearest = null;
        double distance = 128 * 128;
        for (ChunkMemory memory : agent.getAllChunkMemories()) {
            BlockPos anchor = memory.anchorOf(ChunkFeature.FARMLAND);
            if (anchor == null || !world.isLoaded(anchor)
                    || agent.brain().farmRevisitAfter.containsKey(anchor)) continue;
            double d = anchor.distSqr(self.blockPosition());
            if (d < distance) { distance = d; nearest = anchor; }
        }
        agent.brain().rememberedFarm = nearest;
    }

    /** Nearest remembered field anchor in any chunk memory (revisit deferrals ignored), or null. */
    public static BlockPos nearestField(VillagerAgentData agent, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (ChunkMemory memory : agent.getAllChunkMemories()) {
            BlockPos anchor = memory.anchorOf(ChunkFeature.FARMLAND);
            if (anchor == null) continue;
            double d = anchor.distSqr(from);
            if (d < bestD) { bestD = d; best = anchor; }
        }
        return best;
    }

    /** Below this many remembered farmland blocks a farmer opens new land beside water (no field needed). */
    public static final int BOOTSTRAP_FIELD_SIZE = 16;

    public static boolean bootstrapping(VillagerAgentData agent) {
        return agent.brain().knownFarmland < BOOTSTRAP_FIELD_SIZE;
    }

    /** Distinct loaded farmland blocks within 8 blocks of each remembered field anchor. */
    public static int knownFarmland(VillagerAgentData agent, ServerWorld world) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (ChunkMemory memory : agent.getAllChunkMemories()) {
            BlockPos anchor = memory.anchorOf(ChunkFeature.FARMLAND);
            if (anchor == null || !world.isLoaded(anchor)) continue;
            for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-8, -2, -8), anchor.offset(8, 2, 8)))
                if (world.isLoaded(p) && world.getBlockState(p).getBlock() == Blocks.FARMLAND) seen.add(p.asLong());
            if (seen.size() >= 64) break;
        }
        return seen.size();
    }

    /**
     * Tillable ground (air above) next to surface water, nearest to the farmer's job site (else village centre,
     * else the farmer). Water is found first, then its 8 neighbours at the same height are checked.
     */
    public static BlockPos findBootstrapSite(VillagerAgentData agent, VillagerEntity self, ServerWorld world) {
        BlockPos origin = null;
        try {
            java.util.Optional<net.minecraft.util.math.GlobalPos> job = self.getBrain().getMemory(
                    net.minecraft.entity.ai.brain.memory.MemoryModuleType.JOB_SITE);
            if (job.isPresent() && job.get().dimension() == world.dimension()) origin = job.get().pos();
        } catch (RuntimeException ignored) {}
        if (origin == null) {
            VillageMarkets.Market market = VillageMarkets.market(world, self.getUUID());
            origin = market != null ? market.center : self.blockPosition();
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int waters = 0;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-24, -4, -24), origin.offset(24, 4, 24))) {
            if (!world.isLoaded(p) || world.getBlockState(p).getBlock() != Blocks.WATER) continue;
            if (++waters > 400) break;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.offset(dx, 0, dz);
                if ((dx == 0 && dz == 0) || !FarmingAction.isTillableDirt(world, n) || !world.isEmptyBlock(n.above())) continue;
                double d = n.distSqr(origin);
                if (d < bestD) { bestD = d; best = n.immutable(); }
            }
        }
        return best;
    }

    public static void validateArrival(VillagerAgentData agent, BlockPos anchor, ServerWorld world) {
        if (!world.isLoaded(anchor) || world.getBlockState(anchor).getBlock() == Blocks.FARMLAND) return;
        ChunkMemory memory = agent.getChunkMemory(anchor.getX() >> 4, anchor.getZ() >> 4);
        if (memory != null) memory.forgetFeature(ChunkFeature.FARMLAND);
        BrainLog.diagnostic(agent, "farm-memory-forget", "anchor=" + anchor + " reason=field removed");
    }

    public static void defer(VillagerAgentData agent, BlockPos anchor, long now, String reason) {
        agent.brain().farmRevisitAfter.put(anchor.immutable(), now + 1200);
        agent.brain().rememberedFarm = null;
        BrainLog.diagnostic(agent, "farm-memory-defer", "anchor=" + anchor + " reason=" + reason + " retryAt=" + (now + 1200));
    }
    private FarmMemory() {}
}
