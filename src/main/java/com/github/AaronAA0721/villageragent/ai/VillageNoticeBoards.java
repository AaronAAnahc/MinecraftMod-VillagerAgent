package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.content.Workstations;
import com.github.AaronAA0721.villageragent.ai.behavior.FailureReason;
import com.github.AaronAA0721.villageragent.ai.behavior.SkillResult;
import net.minecraft.block.Blocks;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.*;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.village.*;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

/** Physical access to a village's economic notices. Combat signals remain independent. */
public final class VillageNoticeBoards {
    private VillageNoticeBoards() {}

    /** Called by market maintenance, never by cost estimation. Existing villages are supported. */
    public static void ensure(ServerWorld world, VillageMarkets.Market market) {
        VillageRegistry registry = VillageRegistry.get(world);
        BlockPos registered = registry.noticeBoard(market.id);
        if (registered != null) {
            // Keep a live board; forget one that was broken or replaced so a new one can be placed or adopted.
            if (!world.isLoaded(registered) || world.getBlockState(registered).is(Workstations.NOTICE_BOARD.get())) return;
            registry.clearNoticeBoard(market.id);
        }
        BlockPos anchor = world.getPoiManager().findAllClosestFirst(t -> t == PointOfInterestType.MEETING,
                p -> world.isLoaded(p) && registry.bounds(market.id).isInside(p), market.center, 128,
                PointOfInterestManager.Status.ANY).findFirst().orElse(market.center);
        for (int radius = 2; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos column = anchor.offset(dx, 0, dz);
                if (!world.hasChunk(column.getX() >> 4, column.getZ() >> 4)) continue;
                BlockPos base = world.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
                if (!registry.bounds(market.id).isInside(base) || !world.isEmptyBlock(base)
                        || !world.isEmptyBlock(base.above()) || !world.getFluidState(base.below()).isEmpty()
                        || !world.getBlockState(base.below()).isFaceSturdy(world, base.below(), Direction.UP)
                        // The top block must be natural ground: the heightmap also stops on roofs (the well's
                        // roof, houses), where villagers cannot reach the board.
                        || !naturalGround(world.getBlockState(base.below()))) continue;
                boolean approach = false;
                for (Direction d : Direction.Plane.HORIZONTAL)
                    if (standable(world, base.relative(d)) && naturalGround(world.getBlockState(base.relative(d).below()))) approach = true;
                if (!approach) continue;
                if (!world.setBlock(base, Blocks.OAK_FENCE.defaultBlockState(), 3)) continue;
                if (!world.setBlock(base.above(), Workstations.NOTICE_BOARD.get().defaultBlockState(), 3)) {
                    world.removeBlock(base, false); continue;
                }
                registry.setNoticeBoard(market.id, base.above());
                return;
            }
        }
    }
    /** Ground a village stands on (not a roof, wall or farmland). */
    static boolean naturalGround(net.minecraft.block.BlockState state) {
        net.minecraft.block.Block b = state.getBlock();
        return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.PODZOL
                || b == Blocks.GRASS_PATH || b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.GRAVEL
                || b == Blocks.SNOW_BLOCK || b == Blocks.MYCELIUM || b == Blocks.STONE
                || b == Blocks.TERRACOTTA || b == Blocks.SANDSTONE;
    }

    /**
     * A notice board placed by a player becomes its village's board (village containing it, else the nearest
     * village within 32 blocks). The previous board block stays as decoration.
     * @return the village key, or null when no village is near
     */
    public static Long adopt(ServerWorld world, BlockPos pos) {
        VillageRegistry registry = VillageRegistry.get(world);
        Long key = registry.villageNear(world, pos, 32);
        if (key == null) return null;
        registry.setNoticeBoard(key, pos);
        return key;
    }

    /** A broken board is forgotten so the village gets a new one (or adopts the next one placed). */
    public static void removed(ServerWorld world, BlockPos pos) {
        VillageRegistry registry = VillageRegistry.get(world);
        Long key = registry.villageWithBoard(pos);
        if (key != null) registry.clearNoticeBoard(key);
    }

    public static BlockPos position(ServerWorld world, VillagerEntity villager) {
        VillageMarkets.Market market = VillageMarkets.market(world, villager.getUUID());
        if (market == null) return null;
        BlockPos pos = VillageRegistry.get(world).noticeBoard(market.id);
        return pos != null && world.isLoaded(pos) && world.getBlockState(pos).is(Workstations.NOTICE_BOARD.get()) ? pos : null;
    }
    public static boolean atBoard(ServerWorld world, VillagerEntity villager) {
        BlockPos pos = position(world, villager);
        return pos != null && villager.position().distanceToSqr(Vector3d.atCenterOf(pos)) <= 9
                && world.clip(new RayTraceContext(villager.getEyePosition(1), Vector3d.atCenterOf(pos),
                    RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, villager)).getBlockPos().equals(pos);
    }
    public static double distance(ServerWorld world, VillagerEntity villager) {
        if (atBoard(world, villager)) return 0;
        BlockPos target = approachTarget(world, villager);
        if (target == null) return Double.POSITIVE_INFINITY;
        double path = ReachablePaths.distance(villager, target);
        // An unreachable probe is not proof: the isolated search covers a limited region, so a villager down
        // in the shaft it just dug probes as unreachable from every cell. Estimating optimistically keeps
        // "ask the village" among the acquisition methods; the walk itself reports a real failure.
        return Double.isInfinite(path) ? Math.sqrt(villager.distanceToSqr(Vector3d.atCenterOf(target))) : path;
    }
    /**
     * Where a villager stands to read the board: the cheapest cell in front of it that it can path to, and
     * failing that the nearest standing cell, so the villager walks and the move goal reports an honest
     * UNREACHABLE instead of the skill failing on the spot without even trying. Null only when the board has
     * no standing cell at all (walled in, replaced terrain).
     */
    public static BlockPos approachTarget(ServerWorld world, VillagerEntity villager) {
        BlockPos board = position(world,villager), best=null, nearest=null;
        if (board == null) return null;
        double cost=Double.POSITIVE_INFINITY, line=Double.POSITIVE_INFINITY;
        for(Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos candidate=board.below().relative(direction);
            if(!standable(world,candidate)) continue;
            double straight=villager.distanceToSqr(Vector3d.atCenterOf(candidate));
            if(straight<line) { nearest=candidate;line=straight; }
            double next=ReachablePaths.distance(villager,candidate);
            if(next<cost) { best=candidate;cost=next; }
        }
        return best != null ? best : nearest;
    }
    private static boolean standable(ServerWorld world, BlockPos pos) {
        return world.isLoaded(pos) && world.isEmptyBlock(pos) && world.isEmptyBlock(pos.above())
                && world.getBlockState(pos.below()).isFaceSturdy(world, pos.below(), Direction.UP);
    }
    /** Ticks a villager stands in front of the board reading it before acting on what it saw. */
    public static final int READ_TICKS = 60;
    /** Per-visit dwell state owned by the caller (task frame or method attempt). */
    public static final class Reading { long started = -1; public void reset() { started = -1; } }

    /**
     * Walk to the board, then spend {@link #READ_TICKS} looking at it. SUCCESS only after the dwell; all
     * reading, publication and claiming happen at that point, never remotely.
     */
    public static SkillResult read(ServerWorld world, VillagerEntity villager, VillagerAgentData agent, AgentGoal goal, Reading reading) {
        SkillResult arrival = approach(world, villager, agent, goal);
        if (arrival != SkillResult.SUCCESS) { reading.reset(); return arrival; }
        long now = world.getGameTime();
        if (reading.started < 0) reading.started = now;
        BlockPos board = position(world, villager);
        if (board != null) villager.getLookControl().setLookAt(board.getX() + 0.5, board.getY() + 0.5, board.getZ() + 0.5);
        if (now - reading.started < READ_TICKS) return SkillResult.RUNNING;
        reading.reset();
        return SkillResult.SUCCESS;
    }

    public static SkillResult approach(ServerWorld world, VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        if (atBoard(world, villager)) {
            ActionLifecycle.clearBody(agent, villager, world);
            return SkillResult.SUCCESS;
        }
        BlockPos board = position(world, villager);
        if (board == null) {
            goal.setLastFailure(FailureReason.UNREACHABLE.with("no village notice board"));
            return SkillResult.FAIL;
        }
        BlockPos target = goal.getTargetLocation();
        if (target == null || target.distSqr(board) > 9 || !standable(world, target)) {
            target = approachTarget(world, villager);
            if (target == null) {
                // Named, because an unexplained board failure is indistinguishable from a quiet villager.
                goal.setLastFailure(FailureReason.UNREACHABLE.with("no standing cell at the notice board"));
                BrainLog.diagnostic(agent, "board-approach", "board=" + board + " reason=no_standing_cell");
                return SkillResult.FAIL;
            }
            goal.setTargetLocation(target);
        }
        goal.setArrivalDistanceSq(0.5);
        SkillResult result = VillagerAgentManager.driveMoveGoal(villager, agent, goal, target, "Reached village notice board");
        goal.setCompleted(false);
        return result == SkillResult.SUCCESS ? (atBoard(world, villager) ? SkillResult.SUCCESS : SkillResult.FAIL) : result;
    }
}
