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
            t = pickWanderTarget(agent, villager, world);
            if (t == null) {
                BrainLog.diagnostic(agent, "wander-empty", "no target at " + villager.blockPosition());
                goal.setLastFailure(FailureReason.UNREACHABLE.with("no reachable wander destination"));
                return SkillResult.FAIL;
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

        List<Long> frontier = new ArrayList<>(unvisited);
        Collections.shuffle(frontier, r);
        Collections.shuffle(stale, r);
        Collections.shuffle(fresh, r);
        int probes = 0;
        for (List<Long> tier : java.util.Arrays.asList(frontier, stale, fresh)) {
            for (long key : tier) {
                if (probes >= 8) break;
                int x = ChunkPos.getX(key) * 16 + r.nextInt(16);
                int z = ChunkPos.getZ(key) * 16 + r.nextInt(16);
                BlockPos candidate = loadedSurface(world, here, x, z);
                if (candidate == null || horizontalDistanceSq(here, candidate) < 8 * 8) continue;
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
        BrainLog.diagnostic(agent, "wander-no-path", "from=" + here + " frontier=" + frontier.size()
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
        Path path = villager.getNavigation().createPath(target, 0);
        return path != null && path.canReach();
    }
}
