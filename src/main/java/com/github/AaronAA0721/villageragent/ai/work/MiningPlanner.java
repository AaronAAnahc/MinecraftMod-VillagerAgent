package com.github.AaronAA0721.villageragent.ai.work;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.memory.*;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Rarity selects a district; clearing a district uses distance, never global rarity. */
public final class MiningPlanner {
    public static final String[] ORES = {"minecraft:diamond", "minecraft:gold_ore", "minecraft:redstone",
            "minecraft:lapis_lazuli", "minecraft:iron_ore", "minecraft:coal"};
    private static final ChunkFeature[] FEATURES = {ChunkFeature.ORE_DIAMOND, ChunkFeature.ORE_GOLD,
            ChunkFeature.ORE_REDSTONE, ChunkFeature.ORE_LAPIS, ChunkFeature.ORE_IRON, ChunkFeature.ORE_COAL};
    public static List<ResourceCatalog.Source> eligible(VillagerAgentData agent, String only) {
        List<ResourceCatalog.Source> result = new ArrayList<>();
        for (String item : ORES) {
            ResourceCatalog.Source s = ResourceCatalog.get(item);
            if ((only == null || item.equals(only)) && s != null && s.skilled(agent) && s.hasTool(agent)) result.add(s);
        }
        return result;
    }
    public static long knowledgeFingerprint(VillagerAgentData agent) {
        long hash = 0;
        for (ChunkMemory memory : agent.getAllChunkMemories()) for (int i = 0; i < FEATURES.length; i++) {
            BlockPos anchor = memory.anchorOf(FEATURES[i]);
            if (anchor != null) hash += (anchor.asLong() * 31 + i + 1) * 0x9e3779b97f4a7c15L;
        }
        return hash;
    }
    public static final class Visit {
        public final BlockPos anchor;
        public final String item, key;
        final int rank;
        Visit(BlockPos anchor, String item, int rank) {
            this.anchor = anchor; this.item = item; this.rank = rank;
            key = item + "@" + anchor.asLong();
        }
    }
    /** Shared ordering rule: rarity dominates only outside an active district. */
    public static boolean better(boolean global, int rank, double distance, int bestRank, double bestDistance) {
        return Double.isFinite(distance) && (global && rank < bestRank
                || (!global || rank == bestRank) && distance < bestDistance);
    }
    /** Bounded path probes across ticks. Same-rarity known anchors compete by complete path. */
    public static final class Search {
        private final List<Visit> visits = new ArrayList<>();
        private final boolean global;
        private int index;
        public Visit best;
        public BlockPos stand;
        private double cost = Double.POSITIVE_INFINITY;
        public Search(VillagerAgentData a, MiningProgress progress, long now, boolean global) {
            this.global = global;
            String only = progress.phase == MiningProgress.Phase.PRIMARY ? progress.primary : null;
            for (int rank = 0; rank < ORES.length; rank++) {
                ResourceCatalog.Source source = ResourceCatalog.get(ORES[rank]);
                if (source == null || !source.skilled(a) || !source.hasTool(a)
                        || (!global && only != null && !only.equals(ORES[rank]))) continue;
                for (ChunkMemory memory : a.getAllChunkMemories()) {
                    BlockPos anchor = memory.anchorOf(FEATURES[rank]);
                    if (anchor == null || (!global && !progress.inArea(anchor))) continue;
                    Visit visit = new Visit(anchor, ORES[rank], rank);
                    if (progress.ready(visit.key, now)) visits.add(visit);
                }
            }
        }
        public boolean tick(ServerWorld world, VillagerEntity villager, MiningProgress progress) {
            if (index >= visits.size()) return true;
            Visit next = visits.get(index);
            if (global && best != null && next.rank != best.rank) return true;
            index++;
            if (!world.isLoaded(next.anchor)) { progress.defer(next.key, world.getGameTime() + 200); return index >= visits.size(); }
            BlockPos cell = ReachablePaths.standingBeside(world, villager, next.anchor);
            double length = ReachablePaths.distance(villager, cell);
            if (better(global, next.rank, length, best == null ? Integer.MAX_VALUE : best.rank, cost)) {
                best = next; stand = cell; cost = length;
            }
            else if (!Double.isFinite(length)) progress.defer(next.key, world.getGameTime() + 1200);
            return index >= visits.size();
        }
    }
    private MiningPlanner() {}
}
