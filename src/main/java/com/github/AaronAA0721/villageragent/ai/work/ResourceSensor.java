package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.BlockInteractionAction;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import net.minecraft.block.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.*;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Bounded exposed-resource sensor. A worker may walk around an obstacle to an exposed block,
 * but fully buried ore is never proposed. Only loaded chunks are inspected. */
public final class ResourceSensor {
    public static final class Target {
        public final BlockPos block, stand;
        private Target(BlockPos block, BlockPos stand) { this.block = block; this.stand = stand; }
    }

    public static Target find(ServerWorld world, VillagerEntity self, VillagerAgentData agent,
                                ResourceCatalog.Source source, Set<BlockPos> rejected) {
        BlockPos origin = self.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int matching = 0, natural = 0, exposed = 0, standable = 0, pathProbes = 0;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-10, -4, -10), origin.offset(10, 5, 10))) {
            if (p.equals(origin.below()) || !world.isLoaded(p) || rejected.contains(p) || world.getBlockState(p).getBlock() != source.block) continue;
            matching++;
            if (source.profession == ResourceProfession.LUMBERJACK && !nearLeaves(world, p)) continue;
            natural++;
            if (isExposed(world, p)) {
                exposed++;
                candidates.add(p.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        int candidateProbes = 0;
        for (BlockPos p : candidates) {
            if (++candidateProbes > 8 || pathProbes >= 24) break;
            List<BlockPos> stands = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos cell = p.offset(dx, dy, dz);
                if (world.isLoaded(cell) && BlockInteractionAction.isStandable(world, cell)) stands.add(cell);
            }
            stands.sort(Comparator.comparingDouble(cell -> cell.distSqr(origin)));
            standable += stands.size();
            for (int i = 0; i < Math.min(4, stands.size()) && pathProbes < 24; i++) {
                BlockPos stand = stands.get(i);
                pathProbes++;
                Path path = self.getNavigation().createPath(stand, 0);
                if (path != null && path.canReach()) {
                    BrainLog.diagnostic(agent, "resource-scan", "source=" + source.block.getRegistryName()
                            + " origin=" + origin + " matching=" + matching + " natural=" + natural
                            + " exposed=" + exposed + " standable=" + standable + " candidateProbes="
                            + candidateProbes + " pathProbes=" + pathProbes + " reachable=" + p + " stand=" + stand);
                    return new Target(p, stand);
                }
            }
        }
        BrainLog.diagnostic(agent, "resource-scan", "source=" + source.block.getRegistryName()
                + " origin=" + origin + " matching=" + matching + " natural=" + natural
                + " exposed=" + exposed + " standable=" + standable + " candidateProbes="
                + Math.min(candidateProbes, 8) + " pathProbes=" + pathProbes
                + " rejected=" + rejected.size() + " reachable=none");
        return null;
    }
    private static boolean isExposed(ServerWorld world, BlockPos p) {
        for (net.minecraft.util.Direction direction : net.minecraft.util.Direction.values()) {
            BlockPos side = p.relative(direction);
            if (world.isLoaded(side) && world.isEmptyBlock(side)) return true;
        }
        return false;
    }
    private static boolean nearLeaves(ServerWorld world, BlockPos log) {
        for (BlockPos p : BlockPos.betweenClosed(log.offset(-2, -1, -2), log.offset(2, 5, 2))) {
            if (world.isLoaded(p) && world.getBlockState(p).getBlock() instanceof LeavesBlock
                    && !world.getBlockState(p).getValue(LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }
    private ResourceSensor() {}
}
