package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.BrainLog;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.pathfinding.Path;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * "wander" goal: pick a reasonably distant point and walk there, for the sake of wandering
 * (not to gather / trade / farm — just to roam and, as a side effect, expand the villager's
 * explored-chunk map).
 *
 * <p>The destination is drawn from the villager's explored chunks in three tiers:
 * <ol>
 *   <li><b>UNVISITED</b> — a chunk adjacent to an already-explored chunk the villager has never
 *       entered (expands the known map outward, frontier-style).</li>
 *   <li><b>STALE</b> — an explored chunk not visited for a long time (re-visits old ground).</li>
 *   <li><b>FRESH</b> — a recently-visited chunk (re-treads familiar ground).</li>
 * </ol>
 * Only loaded, reachable candidates are considered. Historical chunk memory can contain distant
 * or unloaded locations; querying their height previously returned y=0 and stranded villagers.
 */
public class WanderSkill implements VillagerSkill {

    /** A chunk not visited for this many game ticks counts as STALE (~1 in-game hour @ 20 tps). */
    private static final long STALE_THRESHOLD = 72_000L;
    @Override public String id() { return "wander"; }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        return a.getFatigue() > 60 ? 0.25 : 1.0; // tired villagers wander less
    }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "wander".equals(goal.getGoalType());
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        BlockPos t = goal.getTargetLocation();
        if (t != null && (t.getY() <= 1 || !world.isLoaded(t) || t.distSqr(villager.blockPosition()) > 64 * 64)) {
            BrainLog.diagnostic(agent, "wander-invalid-target", "from=" + villager.blockPosition()
                    + " oldTarget=" + t + " loaded=" + world.isLoaded(t));
            goal.setTargetLocation(null);
            t = null;
        }
        if (t == null) {
            t = pickDestination(agent, villager, world);
            if (t == null) {
                BrainLog.diagnostic(agent, "wander-empty", "no target at " + villager.blockPosition());
                goal.setLastFailure(FailureReason.UNREACHABLE.with("no reachable wander destination"));
                return SkillResult.FAIL;
            }
            // When tethered (e.g. waiting for a furnace), clamp the destination inside the tether radius.
            if (agent.brain().isTethered() && !agent.brain().withinTether(t)) {
                t = null;
                // Fall through to a nearby random point within tether range.
                BlockPos anchor = agent.brain().tetherPos;
                java.util.Random r = new java.util.Random();
                for (int i = 0; i < 8; i++) {
                    double ang = r.nextDouble() * Math.PI * 2;
                    int radius = agent.brain().tetherRadius;
                    int x = anchor.getX() + (int)(Math.cos(ang) * r.nextInt(Math.max(1, radius)));
                    int z = anchor.getZ() + (int)(Math.sin(ang) * r.nextInt(Math.max(1, radius)));
                    BlockPos c = loadedSurface(world, villager.blockPosition(), x, z);
                    if (c != null && canReach(villager, c)) { t = c; break; }
                }
                if (t == null) return SkillResult.FAIL;
            }
            goal.setTargetLocation(t); // pin it so we don't reroll every tick
            BrainLog.diagnostic(agent, "wander-target", "from=" + villager.blockPosition()
                    + " target=" + t + " knownChunks=" + agent.getAllChunkMemories().size());
        }
        SkillResult moved = VillagerAgentManager.driveMoveGoal(villager, agent, goal, t,
                "Arrived at wander spot " + t.getX() + "," + t.getZ());
        if (moved == SkillResult.FAIL) {
            goal.setTargetLocation(null);
            return SkillResult.FAIL;
        }
        return moved;
    }

    private static BlockPos pickWanderTarget(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        Collection<ChunkMemory> known = agent.getAllChunkMemories();
        long now = world.getGameTime();
        BlockPos here = villager.blockPosition();
        int homeCx = here.getX() >> 4, homeCz = here.getZ() >> 4;
        Random r = new Random();

        // Bucket the villager's known chunks into the three tiers.
        Set<Long> knownKeys = new HashSet<>();
        List<Long> stale = new ArrayList<>();
        List<Long> fresh = new ArrayList<>();
        for (ChunkMemory cm : known) {
            int cx = ChunkPos.getX(cm.chunkKey), cz = ChunkPos.getZ(cm.chunkKey);
            if (Math.abs(cx - homeCx) > 3 || Math.abs(cz - homeCz) > 3
                    || !world.isLoaded(new BlockPos(cx * 16 + 8, here.getY(), cz * 16 + 8))) continue;
            knownKeys.add(cm.chunkKey);
            if (now - cm.lastVisitedTick > STALE_THRESHOLD) stale.add(cm.chunkKey);
            else fresh.add(cm.chunkKey);
        }

        // UNVISITED = 8-neighbours of known chunks that are themselves not known.
        Set<Long> unvisited = new HashSet<>();
        for (long key : knownKeys) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long nk = ChunkPos.asLong(cx + dx, cz + dz);
                    if (!knownKeys.contains(nk) && Math.abs(cx + dx - homeCx) <= 3
                            && Math.abs(cz + dz - homeCz) <= 3
                            && world.isLoaded(new BlockPos((cx + dx) * 16 + 8, here.getY(), (cz + dz) * 16 + 8)))
                        unvisited.add(nk);
                }
            }
        }

        // Split frontier into village-interior and exterior. Prioritize interior exploration
        // so villagers map their own village first before wandering into the wilderness.
        BlockPos villageCenter = findVillageCenter(agent, villager, world);
        int villageCxMin = Integer.MIN_VALUE, villageCxMax = Integer.MAX_VALUE;
        int villageCzMin = Integer.MIN_VALUE, villageCzMax = Integer.MAX_VALUE;
        if (villageCenter != null) {
            int vcx = villageCenter.getX() >> 4, vcz = villageCenter.getZ() >> 4;
            villageCxMin = vcx - 4; villageCxMax = vcx + 4; // ~64 blocks each direction
            villageCzMin = vcz - 4; villageCzMax = vcz + 4;
        }

        List<Long> frontierVillage = new ArrayList<>();
        List<Long> frontierOutside = new ArrayList<>();
        for (long key : unvisited) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            if (villageCenter != null && cx >= villageCxMin && cx <= villageCxMax
                    && cz >= villageCzMin && cz <= villageCzMax) {
                frontierVillage.add(key);
            } else {
                frontierOutside.add(key);
            }
        }
        Collections.shuffle(frontierVillage, r);
        Collections.shuffle(frontierOutside, r);
        Collections.shuffle(stale, r);
        Collections.shuffle(fresh, r);
        int probes = 0;
        // Priority order: village frontier → outside frontier → stale → fresh.
        for (List<Long> tier : java.util.Arrays.asList(frontierVillage, frontierOutside, stale, fresh)) {
            for (long key : tier) {
                if (probes >= 8) break;
                int x = ChunkPos.getX(key) * 16 + r.nextInt(16);
                int z = ChunkPos.getZ(key) * 16 + r.nextInt(16);
                BlockPos candidate = loadedSurface(world, here, x, z);
                double distSq = horizontalDistanceSq(here, candidate);
                // Min 8 blocks (meaningful walk), max 40 blocks (within vanilla path-finding range).
                if (candidate == null || distSq < 8 * 8 || distSq > 40 * 40) continue;
                probes++;
                if (canReach(villager, candidate)) return candidate;
            }
        }
        // Nearby loaded ground is a useful fallback when the frontier is beyond a cliff or door.
        for (int i = 0; i < 12 && probes < 12; i++) {
            BlockPos candidate = randomNearPoint(here, world, r);
            if (candidate == null) continue;
            probes++;
            if (canReach(villager, candidate)) return candidate;
        }
        BrainLog.diagnostic(agent, "wander-no-path", "from=" + here + " frontierVillage=" + frontierVillage.size()
                + " frontierOutside=" + frontierOutside.size()
                + " stale=" + stale.size() + " fresh=" + fresh.size() + " pathProbes=" + probes);
        return null;
    }

    private static BlockPos randomNearPoint(BlockPos here, ServerWorld world, Random r) {
        double ang = r.nextDouble() * Math.PI * 2.0;
        double dist = 8 + r.nextDouble() * 16;
        int x = here.getX() + (int) Math.round(Math.cos(ang) * dist);
        int z = here.getZ() + (int) Math.round(Math.sin(ang) * dist);
        return loadedSurface(world, here, x, z);
    }

    /** Chance that a wander stays inside the village rather than exploring its outskirts. */
    private static final double VILLAGE_WANDER_CHANCE = 0.8;
    /** Exploration never ends further than this from the village anchor. */
    private static final int MAX_FROM_VILLAGE = 40;

    /**
     * Village-first destination: usually a spot inside the village (notice board, meeting point, job site, or
     * a random reachable point within the village bounds); sometimes an exploration target, but only one that
     * stays within {@link #MAX_FROM_VILLAGE} blocks of the village. A villager already outside that range
     * always heads back in.
     */
    private static BlockPos pickDestination(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        BlockPos anchor = villageAnchor(agent, villager, world);
        if (anchor == null) return pickWanderTarget(agent, villager, world); // not a village resident
        Random r = villager.getRandom();
        boolean away = horizontalDistanceSq(villager.blockPosition(), anchor) > MAX_FROM_VILLAGE * MAX_FROM_VILLAGE;
        if (away || r.nextDouble() < VILLAGE_WANDER_CHANCE) {
            BlockPos inside = villageSpot(agent, villager, world, anchor);
            if (inside != null) return inside;
        }
        BlockPos explore = pickWanderTarget(agent, villager, world);
        if (explore != null && horizontalDistanceSq(explore, anchor) <= MAX_FROM_VILLAGE * MAX_FROM_VILLAGE) return explore;
        return villageSpot(agent, villager, world, anchor);
    }

    /** Village centre: market centre, else the vanilla meeting point (bell), home or job site. */
    private static BlockPos villageAnchor(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        BlockPos center = findVillageCenter(agent, villager, world);
        if (center != null) return center;
        for (net.minecraft.entity.ai.brain.memory.MemoryModuleType<net.minecraft.util.math.GlobalPos> type
                : java.util.Arrays.asList(net.minecraft.entity.ai.brain.memory.MemoryModuleType.MEETING_POINT,
                        net.minecraft.entity.ai.brain.memory.MemoryModuleType.HOME,
                        net.minecraft.entity.ai.brain.memory.MemoryModuleType.JOB_SITE)) {
            try {
                java.util.Optional<net.minecraft.util.math.GlobalPos> pos = villager.getBrain().getMemory(type);
                if (pos.isPresent() && pos.get().dimension() == world.dimension()) return pos.get().pos();
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    /** A reachable spot in the village, 6+ blocks from the villager; points of interest are favoured. */
    private static BlockPos villageSpot(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, BlockPos anchor) {
        Random r = villager.getRandom();
        BlockPos here = villager.blockPosition();
        List<BlockPos> interest = new ArrayList<>();
        com.github.AaronAA0721.villageragent.ai.VillageMarkets.Market market =
                com.github.AaronAA0721.villageragent.ai.VillageMarkets.market(world, villager.getUUID());
        net.minecraft.util.math.MutableBoundingBox box = null;
        if (market != null) {
            com.github.AaronAA0721.villageragent.ai.VillageRegistry registry =
                    com.github.AaronAA0721.villageragent.ai.VillageRegistry.get(world);
            try { box = registry.bounds(market.id); } catch (RuntimeException ignored) {}
            BlockPos board = registry.noticeBoard(market.id);
            if (board != null) interest.add(board);
        }
        interest.add(anchor);
        try {
            villager.getBrain().getMemory(net.minecraft.entity.ai.brain.memory.MemoryModuleType.MEETING_POINT)
                    .ifPresent(g -> { if (g.dimension() == world.dimension()) interest.add(g.pos()); });
        } catch (RuntimeException ignored) {}
        for (int i = 0; i < 10; i++) {
            int x, z;
            if (i < 3 && r.nextDouble() < 0.4) {
                // Near a point of interest: stand a few blocks from it, not on it.
                BlockPos poi = interest.get(r.nextInt(interest.size()));
                x = poi.getX() + r.nextInt(7) - 3; z = poi.getZ() + r.nextInt(7) - 3;
            } else if (box != null) {
                x = box.x0 + r.nextInt(Math.max(1, box.x1 - box.x0 + 1));
                z = box.z0 + r.nextInt(Math.max(1, box.z1 - box.z0 + 1));
            } else {
                x = anchor.getX() + r.nextInt(49) - 24; z = anchor.getZ() + r.nextInt(49) - 24;
            }
            BlockPos c = loadedSurface(world, here, x, z);
            if (c == null) continue;
            double d = horizontalDistanceSq(here, c);
            if (d < 6 * 6 || d > 64 * 64) continue;
            if (canReach(villager, c)) return c;
        }
        return null;
    }

    /**
     * Residents wander inside their village (bounds + 12 blocks). Long exploratory walks took villagers out
     * of the player's simulation distance, where they freeze, and off camera.
     */
    private static BlockPos keepInVillage(VillagerAgentData agent, VillagerEntity villager, ServerWorld world, BlockPos t) {
        com.github.AaronAA0721.villageragent.ai.VillageMarkets.Market market =
                com.github.AaronAA0721.villageragent.ai.VillageMarkets.market(world, villager.getUUID());
        if (market == null) return t;
        net.minecraft.util.math.MutableBoundingBox box =
                com.github.AaronAA0721.villageragent.ai.VillageRegistry.get(world).bounds(market.id);
        if (box == null) return t;
        int margin = 12;
        if (t != null && t.getX() >= box.x0 - margin && t.getX() <= box.x1 + margin
                && t.getZ() >= box.z0 - margin && t.getZ() <= box.z1 + margin) return t;
        java.util.Random r = villager.getRandom();
        for (int i = 0; i < 10; i++) {
            int x = box.x0 + r.nextInt(Math.max(1, box.x1 - box.x0 + 1));
            int z = box.z0 + r.nextInt(Math.max(1, box.z1 - box.z0 + 1));
            BlockPos c = loadedSurface(world, villager.blockPosition(), x, z);
            if (c != null && c.distSqr(villager.blockPosition()) <= 48 * 48 && canReach(villager, c)) return c;
        }
        return t;
    }

    private static BlockPos loadedSurface(ServerWorld world, BlockPos here, int x, int z) {
        if (!world.isLoaded(new BlockPos(x, here.getY(), z))) return null;
        int y = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y > 1 ? new BlockPos(x, y, z) : null;
    }

    private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean canReach(VillagerEntity villager, BlockPos target) {
        Path path = com.github.AaronAA0721.villageragent.ai.VillagerPathfinding.compute(villager, target, 0);
        return path != null && path.canReach();
    }

    /** Find the village center from VillageMarkets membership, or null if not in a village. */
    private static BlockPos findVillageCenter(VillagerAgentData agent, VillagerEntity villager, ServerWorld world) {
        com.github.AaronAA0721.villageragent.ai.VillageMarkets.Market market =
                com.github.AaronAA0721.villageragent.ai.VillageMarkets.market(world, villager.getUUID());
        return market != null ? market.center : null;
    }
}
