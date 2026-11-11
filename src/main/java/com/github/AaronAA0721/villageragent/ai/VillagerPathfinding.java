package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.common.collect.ImmutableSet;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigator;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Region;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Path search that never touches a villager's live {@link PathNavigator} state, plus seamless path
 * swapping and an off-thread search service.
 *
 * <p>Why: {@code PathNavigator.createPath} records the searched target as the navigator's
 * {@code targetPos}. Route-cost probes (resource sensing, notice boards) therefore silently retargeted
 * the villager's walk, so the next ordinary {@code createPath} for the real target built a brand-new
 * path, and vanilla 1.16 makes a mob walk back to the centre of its start node whenever a new path
 * is installed. The result was a visible stop every second or so while walking.
 */
public final class VillagerPathfinding {
    private static final Logger LOGGER = LogManager.getLogger("VillagerPathfinding");
    /** Same extra region margin vanilla uses for {@code createPath(BlockPos, int)}. */
    private static final int REGION_MARGIN = 8;

    private VillagerPathfinding() {}

    // ── Isolated search ─────────────────────────────────────────────────

    private static PathFinder newFinder(MobEntity mob) {
        WalkNodeProcessor evaluator = new WalkNodeProcessor();
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        evaluator.setCanFloat(true);
        int maxNodes = MathHelper.floor(mob.getAttributeBaseValue(Attributes.FOLLOW_RANGE) * 16.0D);
        return new PathFinder(evaluator, Math.max(64, maxNodes));
    }

    private static Region region(ServerWorld world, MobEntity mob) {
        int r = (int) (mob.getAttributeValue(Attributes.FOLLOW_RANGE) + REGION_MARGIN);
        BlockPos origin = mob.blockPosition();
        return new Region(world, origin.offset(-r, -r, -r), origin.offset(r, r, r));
    }

    /** Same target normalisation as {@code GroundPathNavigator.createPath(BlockPos, int)}. */
    static BlockPos groundTarget(ServerWorld world, BlockPos pos) {
        if (world.isEmptyBlock(pos)) {
            BlockPos below = pos.below();
            while (below.getY() > 0 && world.isEmptyBlock(below)) below = below.below();
            if (below.getY() > 0) return below.above();
            while (below.getY() < 255 && world.isEmptyBlock(below)) below = below.above();
            pos = below;
        }
        if (!world.getBlockState(pos).getMaterial().isSolid()) return pos;
        BlockPos above = pos.above();
        while (above.getY() < 255 && world.getBlockState(above).getMaterial().isSolid()) above = above.above();
        return above;
    }

    /** Synchronous search on the server thread. The navigator (path, targetPos, timers) is untouched. */
    public static Path compute(VillagerEntity villager, BlockPos target, int accuracy) {
        if (target == null || !(villager.level instanceof ServerWorld)) return null;
        try {
            ServerWorld world = (ServerWorld) villager.level;
            if (!world.isLoaded(target)) return null;
            target = groundTarget(world, target);
            return newFinder(villager).findPath(region(world, villager), villager, ImmutableSet.of(target),
                    (float) villager.getAttributeValue(Attributes.FOLLOW_RANGE), accuracy, 1.0F);
        } catch (RuntimeException e) {
            LOGGER.debug("Isolated path search failed for {}: {}", villager.getUUID(), e.toString());
            return null;
        }
    }

    // ── Seamless swap ───────────────────────────────────────────────────

    /**
     * Skip leading nodes in the column the villager already stands in. Vanilla 1.16 only advances a node
     * once the mob is within ~0.45 blocks of its centre, so a freshly installed path otherwise pulls the
     * villager back to the middle of its current block — the "stop" seen on every repath.
     */
    public static void skipBehind(MobEntity mob, Path path) {
        if (path == null) return;
        int count = path.getNodeCount();
        int bx = MathHelper.floor(mob.getX()), bz = MathHelper.floor(mob.getZ());
        while (path.getNextNodeIndex() < count - 1) {
            BlockPos node = path.getNodePos(path.getNextNodeIndex());
            if (node.getX() != bx || node.getZ() != bz || Math.abs(node.getY() - mob.getY()) > 1.0) break;
            BlockPos next = path.getNodePos(path.getNextNodeIndex() + 1);
            double dx = next.getX() + 0.5 - mob.getX(), dz = next.getZ() + 0.5 - mob.getZ();
            // Only skip when the following node is an immediate neighbour (no corner cutting through walls).
            if (dx * dx + dz * dz > 2.25) break;
            path.advance();
        }
    }

    /**
     * True when vanilla's corner cut (skip the next node, head for the one after) would walk the villager into
     * a block: the next node is a step up, or the straight line to the node after it crosses a solid cell at
     * body height (pumpkins, melons, open trapdoors, fences...).
     */
    public static boolean unsafeCornerCut(MobEntity mob, Path path) {
        int index = path.getNextNodeIndex();
        if (index + 1 >= path.getNodeCount()) return false;
        BlockPos next = path.getNodePos(index), after = path.getNodePos(index + 1);
        if (next.getY() > mob.getY() + 0.5 || after.getY() > mob.getY() + 0.5) return true;
        net.minecraft.world.World world = mob.level;
        double sx = mob.getX(), sz = mob.getZ(), ex = after.getX() + 0.5, ez = after.getZ() + 0.5;
        double half = mob.getBbWidth() / 2.0;
        int feet = MathHelper.floor(mob.getY() + 0.1);
        for (int i = 1; i <= 8; i++) {
            double t = i / 8.0, x = sx + (ex - sx) * t, z = sz + (ez - sz) * t;
            // Check the corners of the villager's footprint at this point along the line.
            for (int c = 0; c < 4; c++) {
                double cx = x + ((c & 1) == 0 ? -half : half), cz = z + ((c & 2) == 0 ? -half : half);
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos cell = new BlockPos(MathHelper.floor(cx), feet + dy, MathHelper.floor(cz));
                    if (!world.isLoaded(cell)) return true;
                    net.minecraft.util.math.shapes.VoxelShape shape =
                            world.getBlockState(cell).getCollisionShape(world, cell);
                    if (shape.isEmpty()) continue;
                    // Low steps (slabs, paths, carpets, farmland) are walked over; anything taller blocks the cut.
                    if (cell.getY() + shape.max(net.minecraft.util.Direction.Axis.Y) > mob.getY() + 0.6) {
                        // Only if the footprint corner actually overlaps the shape horizontally.
                        net.minecraft.util.math.AxisAlignedBB box = shape.bounds().move(cell);
                        if (cx > box.minX && cx < box.maxX && cz > box.minZ && cz < box.maxZ) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * True when an open trapdoor stands in the body-sized box at (x, y, z): the upright plate is a full block
     * tall, so a villager can neither walk through it nor (on a level path) jump it. Used by
     * {@code WalkNodeProcessorMixin}.
     */
    public static boolean openTrapdoorInBody(net.minecraft.world.IBlockReader world, int x, int y, int z,
                                             int xSize, int ySize, int zSize) {
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dx = 0; dx < Math.max(1, xSize); dx++)
            for (int dy = 0; dy < Math.max(1, ySize); dy++)
                for (int dz = 0; dz < Math.max(1, zSize); dz++) {
                    net.minecraft.block.BlockState state = world.getBlockState(cell.set(x + dx, y + dy, z + dz));
                    if (state.getBlock() instanceof net.minecraft.block.TrapDoorBlock
                            && state.getValue(net.minecraft.block.TrapDoorBlock.OPEN)) return true;
                }
        return false;
    }

    /** Vanilla villager step height; a jump is needed for anything taller. */
    private static final float DEFAULT_STEP = 0.6F;

    /**
     * Step up under a low ceiling. A villager is 1.95 tall: jumping onto a block with only two air blocks above it
     * (block at y, next block at y+3) lifts the head into the ceiling, so the jump only succeeds if the feet are
     * between 2.00 and 2.05 above the start while moving over the edge — practically never. The pathfinder still
     * plans the step (standing on it fits), so the villager pushed against it forever. While the next path node is
     * one block up and either column has a solid cell at jump-apex head height, the villager steps up like a horse
     * (step height 1.0, which needs only the 1.95 that fits) instead of jumping. Otherwise the vanilla step is kept.
     */
    public static void stepAssist(VillagerEntity villager, PathNavigator navigation) {
        float step = DEFAULT_STEP;
        Path path = navigation.getPath();
        if (path != null && !path.isDone() && villager.isOnGround()) {
            int feet = MathHelper.floor(villager.getY() + 0.01);
            for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2); i++) {
                BlockPos node = path.getNodePos(i);
                if (node.getY() != feet + 1) continue;
                double dx = node.getX() + 0.5 - villager.getX(), dz = node.getZ() + 0.5 - villager.getZ();
                if (dx * dx + dz * dz > 4.0) continue;
                BlockPos here = new BlockPos(MathHelper.floor(villager.getX()), feet, MathHelper.floor(villager.getZ()));
                // Jump apex: feet + ~1.25, head + 1.95 -> the cell three above the current feet in either column.
                if (solid(villager, here.above(3)) || solid(villager, node.above(2))) { step = 1.0F; break; }
            }
        }
        if (villager.maxUpStep != step) villager.maxUpStep = step;
    }

    private static boolean solid(MobEntity mob, BlockPos pos) {
        return mob.level.isLoaded(pos) && !mob.level.getBlockState(pos).getCollisionShape(mob.level, pos).isEmpty();
    }

    /** Last speed a navigator was asked to move at; recorded by the PathNavigator mixin. */
    private static final Map<PathNavigator, Double> SPEEDS = new WeakHashMap<>();
    public static void recordSpeed(PathNavigator navigator, double speed) {
        synchronized (SPEEDS) { SPEEDS.put(navigator, speed); }
    }
    public static double lastSpeed(PathNavigator navigator) {
        synchronized (SPEEDS) {
            Double speed = SPEEDS.get(navigator);
            return speed == null ? VillagerNavigationSupport.WALK_SPEED : speed;
        }
    }

    /** Managed adult villager on the server whose navigation this mod owns. */
    public static boolean managed(MobEntity mob) {
        return mob instanceof VillagerEntity && mob.level instanceof ServerWorld && !mob.isBaby()
                && ModConfig.ENABLE_AI_AGENTS.get() && VillagerAgentManager.getAgent(mob.getUUID()) != null;
    }

    // ── Off-thread service ──────────────────────────────────────────────

    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "VillagerAgent-Path-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final class Request {
        final Path basis;
        final Object owner;
        final double speed;
        final long tick;
        final String reason;
        volatile Path result;
        volatile boolean done;
        Request(Path basis, Object owner, double speed, long tick, String reason) {
            this.basis = basis; this.owner = owner; this.speed = speed; this.tick = tick; this.reason = reason;
        }
    }
    private static final Map<UUID, Request> PENDING = new ConcurrentHashMap<>();

    public static boolean asyncEnabled() { return ModConfig.ASYNC_PATHFINDING.get(); }
    public static boolean pending(VillagerEntity villager) { return PENDING.containsKey(villager.getUUID()); }

    /**
     * Search off-thread. The chunk region is captured here (server thread); only the A* search runs on a
     * worker. The result is installed by {@link #poll} only if the navigator still follows {@code basis}
     * and, when {@code owner} is non-null, the agent still runs that action.
     *
     * @return false when a request is already in flight or the region could not be captured.
     */
    public static boolean request(VillagerEntity villager, BlockPos target, int accuracy, double speed,
                                  Object owner, String reason) {
        if (target == null || !(villager.level instanceof ServerWorld)) return false;
        UUID id = villager.getUUID();
        if (PENDING.containsKey(id)) return false;
        ServerWorld world = (ServerWorld) villager.level;
        final Region region;
        final PathFinder finder;
        final float range;
        try {
            region = region(world, villager);
            finder = newFinder(villager);
            range = (float) villager.getAttributeValue(Attributes.FOLLOW_RANGE);
        } catch (RuntimeException e) {
            return false;
        }
        if (!world.isLoaded(target)) return false;
        final BlockPos goal = groundTarget(world, target).immutable();
        Request request = new Request(villager.getNavigation().getPath(), owner, speed, world.getGameTime(), reason);
        PENDING.put(id, request);
        try {
            POOL.execute(() -> {
                try {
                    request.result = finder.findPath(region, villager, ImmutableSet.of(goal), range, accuracy, 1.0F);
                } catch (Throwable t) {
                    // Chunk data changed under the search; the caller simply keeps its current path.
                    request.result = null;
                } finally {
                    request.done = true;
                }
            });
        } catch (RuntimeException rejected) {
            PENDING.remove(id);
            return false;
        }
        return true;
    }

    /** Server thread, every tick: install a finished off-thread path if it is still relevant. */
    public static void poll(VillagerEntity villager, VillagerAgentData agent) {
        Request request = PENDING.get(villager.getUUID());
        if (request == null) return;
        long now = villager.level.getGameTime();
        if (!request.done) {
            // A wedged worker must not block future requests forever.
            if (now - request.tick > 100) PENDING.remove(villager.getUUID());
            return;
        }
        PENDING.remove(villager.getUUID());
        Path result = request.result;
        if (result == null || result.getNodeCount() == 0 || now - request.tick > 40) return;
        PathNavigator navigation = villager.getNavigation();
        Path current = navigation.getPath();
        boolean sameBasis = current == request.basis
                || (request.basis == null && (current == null || current.isDone()));
        if (!sameBasis) return;
        if (request.owner != null && request.owner != agent.getCurrentAction()) return;
        if (villager.isSleeping()) return;
        skipBehind(villager, result);
        boolean started = navigation.moveTo(result, request.speed);
        BrainLog.diagnostic(agent, "navigation-async", "reason=" + request.reason + " started=" + started
                + " nodes=" + result.getNodeCount() + " reach=" + result.canReach()
                + " latency=" + (now - request.tick));
    }

    /** Drop any in-flight request (villager removed). */
    public static void forget(UUID villager) { PENDING.remove(villager); }
}
