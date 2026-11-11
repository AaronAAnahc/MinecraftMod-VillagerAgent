package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Complete vanilla paths, scored by summed node segments. Probes never start movement or touch the navigator. */
public final class ReachablePaths {
    private ReachablePaths() {}
    private static final class Probe {
        final BlockPos origin; final long tick; final double length;
        Probe(BlockPos origin, long tick, double length) { this.origin=origin; this.tick=tick; this.length=length; }
    }
    private static final Map<VillagerEntity, Map<BlockPos, Probe>> CACHE = new WeakHashMap<>();
    public static void invalidate(VillagerEntity villager) { CACHE.remove(villager); }
    public static double distance(VillagerEntity villager, BlockPos target) {
        if (target == null || !villager.level.isLoaded(target)) return Double.POSITIVE_INFINITY;
        if (villager.blockPosition().equals(target)) return 0;
        Map<BlockPos, Probe> probes = CACHE.computeIfAbsent(villager, key -> new HashMap<>());
        Probe old = probes.get(target); long now = villager.level.getGameTime();
        // A walking villager changes block every few ticks; a probe from a nearby origin is still a
        // good cost estimate and avoids re-running A* for every candidate on every scan.
        if (old != null && old.origin.distSqr(villager.blockPosition()) <= 9 && now >= old.tick && now-old.tick < 100) return old.length;
        // Isolated search: never call navigator.createPath here — it overwrites the navigator's target
        // and made the next real repath rebuild (and visibly restart) the villager's walk.
        Path path = VillagerPathfinding.compute(villager, target, 0);
        double length = Double.POSITIVE_INFINITY;
        if (path != null && path.canReach() && path.getNodeCount() > 0
                && path.getNodePos(path.getNodeCount()-1).equals(target)) {
            length=PathLength.measure(villager.getX(),villager.getY(),villager.getZ(),path.getNodeCount(),
                    i -> path.getNodePos(i).getX()+0.5, i -> path.getNodePos(i).getY(), i -> path.getNodePos(i).getZ()+0.5);
        }
        if (probes.size() >= 256) probes.clear();
        probes.put(target.immutable(), new Probe(villager.blockPosition(),now,length));
        return length;
    }
    public static BlockPos standingBeside(ServerWorld world, VillagerEntity villager, BlockPos block) {
        BlockPos best=null; double cost=Double.POSITIVE_INFINITY;
        for (int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) for(int z=-1;z<=1;z++) {
            if(x==0 && y==0 && z==0) continue;
            BlockPos stand=block.offset(x,y,z);
            if(!world.isLoaded(stand) || !BlockInteractionAction.isStandable(world,stand)) continue;
            double next=distance(villager,stand);
            if(next<cost) {best=stand;cost=next;}
        }
        return best;
    }
    /** Loaded forest surface candidates; no unreachable/unknown route gets a straight-line fallback. */
    public static BlockPos forestDestination(ServerWorld world, VillagerEntity villager, int chunkX, int chunkZ) {
        if (!world.hasChunk(chunkX,chunkZ)) return null;
        if ((villager.blockPosition().getX()>>4)==chunkX && (villager.blockPosition().getZ()>>4)==chunkZ)
            return villager.blockPosition();
        BlockPos best=null; double cost=Double.POSITIVE_INFINITY;
        for(int x=4;x<=12;x+=4) for(int z=4;z<=12;z+=4) {
            BlockPos pos=world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,new BlockPos(chunkX*16+x,0,chunkZ*16+z));
            if(!BlockInteractionAction.isStandable(world,pos)) continue;
            double next=distance(villager,pos);
            if(next<cost) {best=pos;cost=next;}
        }
        return best;
    }
}
