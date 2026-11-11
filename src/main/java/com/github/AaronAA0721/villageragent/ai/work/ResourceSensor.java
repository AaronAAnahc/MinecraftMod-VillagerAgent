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
    private static final Map<VillagerEntity, Map<ResourceCatalog.Source, Probe>> PROBES = new WeakHashMap<>();
    private static final class Probe {
        final long tick;
        final BlockPos origin;
        final double distance;
        Probe(long tick, BlockPos origin, double distance) { this.tick = tick; this.origin = origin; this.distance = distance; }
    }
    /** Cost estimation may inspect reachability, but never starts navigation or breaks a block. */
    public static boolean available(ServerWorld world, VillagerEntity self, VillagerAgentData agent, ResourceCatalog.Source source) {
        return Double.isFinite(travelDistance(world, self, agent, source));
    }
    public static double travelDistance(ServerWorld world, VillagerEntity self, VillagerAgentData agent, ResourceCatalog.Source source) {
        Map<ResourceCatalog.Source, Probe> cached = PROBES.computeIfAbsent(self, ignored -> new HashMap<>());
        Probe previous = cached.get(source);
        long now = world.getGameTime();
        if (previous != null && now >= previous.tick && now - previous.tick < 40
                && previous.origin.distSqr(self.blockPosition()) <= 4) return previous.distance;
        BlockPos destination = null;
        if (source.profession == ResourceProfession.LUMBERJACK) {
            com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory memory = TreeFelling.remembered(agent,self,world,Collections.emptySet());
            if (memory != null) destination = com.github.AaronAA0721.villageragent.ai.ReachablePaths.forestDestination(world,self,memory.getX(),memory.getZ());
        } else {
            Target target = find(world,self,agent,source,Collections.emptySet());
            if (target != null) destination = target.stand;
        }
        double distance = com.github.AaronAA0721.villageragent.ai.ReachablePaths.distance(self,destination);
        cached.put(source, new Probe(now, self.blockPosition(), distance));
        return distance;
    }
    public static final class Target {
        public final BlockPos block, stand;
        private Target(BlockPos block, BlockPos stand) { this.block = block; this.stand = stand; }
    }

    public static Target find(ServerWorld world, VillagerEntity self, VillagerAgentData agent,
                                ResourceCatalog.Source source, Set<BlockPos> rejected) {
        BlockPos origin = self.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int matching = 0, natural = 0, exposed = 0, standable = 0, pathProbes = 0;
        boolean logging=source.profession==ResourceProfession.LUMBERJACK;
        MiningWorldData ledger = source.consumesBlock ? null : MiningWorldData.get(world);
        BlockPos min=logging?new BlockPos((origin.getX()>>4)*16,0,(origin.getZ()>>4)*16):origin.offset(-10,-4,-10);
        BlockPos max=logging?min.offset(15,255,15):origin.offset(10,5,10);
        for (BlockPos p : BlockPos.betweenClosed(min,max)) {
            if (p.equals(origin.below()) || !world.isLoaded(p) || rejected.contains(p) || !source.matches(world.getBlockState(p))) continue;
            if (ledger != null && (ledger.mined(self.getUUID(), p)
                    || ledger.occupied(self.getUUID(), p, world.getGameTime()))) continue;
            matching++;
            if(logging && com.github.AaronAA0721.villageragent.ai.VillageRegistry.get(world).contains(world,p)) continue;
            natural++;
            if (isExposed(world, p)) {
                exposed++;
                candidates.add(p.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        Target best = null; double bestCost = Double.POSITIVE_INFINITY;
        int candidateProbes = 0;
        for (BlockPos p : candidates) {
            if (++candidateProbes > 8 || pathProbes >= 24) break;
            if(logging && TreeFelling.scan(world,p)==null) continue;
            List<BlockPos> stands = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos cell = p.offset(dx, dy, dz);
                if (world.isLoaded(cell) && BlockInteractionAction.isStandable(world, cell)) stands.add(cell);
            }

            stands.sort(Comparator.comparingDouble(p2 -> p2.distSqr(origin)));
            standable += stands.size();
            for (int i = 0; i < Math.min(4, stands.size()) && pathProbes < 24; i++) {
                BlockPos stand = stands.get(i);
                pathProbes++;
                Path path = com.github.AaronAA0721.villageragent.ai.VillagerPathfinding.compute(self, stand, 0);
                if (path != null && path.canReach()) {
                    BrainLog.diagnostic(agent, "resource-scan", "source=" + source.block.getRegistryName()
                            + " origin=" + origin + " matching=" + matching + " natural=" + natural
                            + " exposed=" + exposed + " standable=" + standable + " candidateProbes="
                            + candidateProbes + " pathProbes=" + pathProbes + " reachable=" + p + " stand=" + stand);
                    double cost = com.github.AaronAA0721.villageragent.ai.ReachablePaths.distance(self,stand);
                    if (cost < bestCost) { best = new Target(p, stand); bestCost = cost; }
                }
            }
        }
        if (best != null) return best;
        BrainLog.diagnostic(agent, "resource-scan", "source=" + source.block.getRegistryName()
                + " origin=" + origin + " matching=" + matching + " natural=" + natural
                + " exposed=" + exposed + " standable=" + standable + " candidateProbes="
                + Math.min(candidateProbes, 8) + " pathProbes=" + pathProbes
                + " rejected=" + rejected.size() + " reachable=none");
        return null;
    }
    public static boolean isExposed(ServerWorld world, BlockPos p) {
        for (net.minecraft.util.Direction direction : net.minecraft.util.Direction.values()) {
            BlockPos side = p.relative(direction);
            if (world.isLoaded(side) && world.isEmptyBlock(side)) return true;
        }
        return false;
    }

    /** Resumable local survey. Exhausting a tick budget never means exhausting the survey. */
    public static final class MiningScan {
        private final Iterator<BlockPos> cells;
        private final List<ResourceCatalog.Source> sources;
        private final java.util.function.Predicate<BlockPos> inside;
        private final List<Candidate> candidates = new ArrayList<>();
        private boolean collected, done;
        private int candidateIndex, standIndex;
        private List<BlockPos> stands;
        public boolean deferred;
        public Target best;
        public ResourceCatalog.Source source;
        public double cost = Double.POSITIVE_INFINITY;
        private static final class Candidate {
            final BlockPos pos; final ResourceCatalog.Source source;
            Candidate(BlockPos pos, ResourceCatalog.Source source) { this.pos = pos; this.source = source; }
        }
        public MiningScan(BlockPos center, List<ResourceCatalog.Source> sources,
                          java.util.function.Predicate<BlockPos> inside) {
            cells = BlockPos.betweenClosed(center.offset(-10, -4, -10), center.offset(10, 5, 10)).iterator();
            this.sources = sources; this.inside = inside;
        }
        /** World-query seam; survey budgets and selection run identically in rule checks. */
        public interface View {
            BlockPos origin();
            boolean loaded(BlockPos pos);
            ResourceCatalog.Source exposedSource(BlockPos pos);
            boolean mined(BlockPos pos);
            boolean unavailable(BlockPos pos);
            boolean standable(BlockPos pos);
            double distance(BlockPos stand);
            double lowerBoundSquared(BlockPos stand);
        }
        public boolean tick(ServerWorld world, VillagerEntity self, java.util.function.Predicate<BlockPos> rejected) {
            MiningWorldData data = MiningWorldData.get(world);
            return tick(new View() {
                public BlockPos origin() { return self.blockPosition(); }
                public boolean loaded(BlockPos p) { return world.isLoaded(p); }
                public ResourceCatalog.Source exposedSource(BlockPos p) {
                    for (ResourceCatalog.Source s : sources)
                        if (s.matches(world.getBlockState(p)) && isExposed(world, p)) return s;
                    return null;
                }
                public boolean mined(BlockPos p) { return data.mined(self.getUUID(), p); }
                public boolean unavailable(BlockPos p) {
                    return rejected.test(p) || data.occupied(self.getUUID(), p, world.getGameTime());
                }
                public boolean standable(BlockPos p) { return BlockInteractionAction.isStandable(world, p); }
                public double distance(BlockPos p) {
                    return com.github.AaronAA0721.villageragent.ai.ReachablePaths.distance(self, p);
                }
                public double lowerBoundSquared(BlockPos p) {
                    double dx = self.getX() - p.getX() - .5, dy = self.getY() - p.getY();
                    double dz = self.getZ() - p.getZ() - .5;
                    return dx * dx + dy * dy + dz * dz;
                }
            });
        }
        public boolean tick(View view) {
            if (done) return true;
            if (!collected) {
                int budget = 512;
                while (budget-- > 0 && cells.hasNext()) {
                    BlockPos p = cells.next();
                    if (!inside.test(p) || p.getY() < 0 || p.getY() > 255) continue;
                    if (!view.loaded(p)) { deferred = true; continue; }
                    if (p.equals(view.origin().below()) || view.mined(p)) continue;
                    ResourceCatalog.Source s = view.exposedSource(p);
                    if (s != null && sources.contains(s)) {
                        if (view.unavailable(p)) { deferred = true; continue; }
                        candidates.add(new Candidate(p.immutable(), s));
                    }
                }
                if (cells.hasNext()) return false;
                candidates.sort(Comparator.comparingDouble(c -> c.pos.distSqr(view.origin())));
                collected = true;
            }
            int probes = 12;
            while (candidateIndex < candidates.size()) {
                Candidate c = candidates.get(candidateIndex);
                if (!view.loaded(c.pos)) { deferred = true; candidateIndex++; stands = null; continue; }
                if (view.exposedSource(c.pos) != c.source
                        || view.mined(c.pos)) { candidateIndex++; stands = null; continue; }
                if (view.unavailable(c.pos)) {
                    deferred = true; candidateIndex++; stands = null; continue;
                }
                if (stands == null) {
                    stands = new ArrayList<>(); standIndex = 0;
                    for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        BlockPos stand = c.pos.offset(x, y, z);
                        if (view.loaded(stand) && view.standable(stand)) stands.add(stand);
                    }
                    stands.sort(Comparator.comparingDouble(p -> p.distSqr(view.origin())));
                    if (stands.isEmpty()) deferred = true;
                }
                while (standIndex < stands.size()) {
                    if (probes == 0) return false;
                    BlockPos stand = stands.get(standIndex++);
                    // Straight-line distance is a lower bound: skip paths unable to beat the winner.
                    if (view.lowerBoundSquared(stand) >= cost * cost) continue;
                    probes--;
                    double length = view.distance(stand);
                    if (!Double.isFinite(length)) { deferred = true; continue; }
                    if (length < cost) { cost = length; best = new Target(c.pos, stand); source = c.source; }
                }
                candidateIndex++; stands = null;
            }
            done = true; return true;
        }
    }
    private ResourceSensor() {}
}
