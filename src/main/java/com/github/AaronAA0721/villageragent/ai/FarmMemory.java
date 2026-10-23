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
        }
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
