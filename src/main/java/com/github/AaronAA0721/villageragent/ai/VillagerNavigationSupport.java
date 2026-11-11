package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigator;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.Iterator;

/** Restores the door interaction normally performed by the vanilla villager Brain.
 * Movement, step climbing and path following remain owned by vanilla navigation/physics. */
public final class VillagerNavigationSupport {
    /** Path search radius for managed villagers (vanilla villager default is 48). */
    public static final double LONG_FOLLOW_RANGE = 80.0;
    /** Minecraft 1.16.5 VillagerEntity registers ordinary activity packages at 0.5F. */
    public static final double WALK_SPEED = 0.5;

    public static boolean failedWalk(VillagerEntity villager, VillagerAction action, long elapsed) {
        if (action == null || !action.isGoalDriven() || elapsed < 20
                || (action.getActionType() != VillagerAction.ActionType.MOVE
                    && action.getPhase() != VillagerAction.ActionPhase.WALKING)) return false;
        if (action.hasNavigationFailed()) return true;
        BlockPos target = action.getStandCell() != null ? action.getStandCell() : action.getTargetBlockPos();
        Path path = villager.getNavigation().getPath();
        if (target == null && path != null) target = path.getTarget();
        if (target == null) return false;
        double radiusSq = action.isRestMove() ? 4 : action.isFarmMove() ? 2 : action.getStandCell() != null ? 1 : 16;
        if (villager.blockPosition().distSqr(target) <= radiusSq) return false;
        if (path == null || path.getNodeCount() == 0) return true;
        // A partial path is sufficient for interaction only if its endpoint is in reach.
        return villager.getNavigation().isDone() && !path.canReach() && path.getNodePos(path.getNodeCount() - 1).distSqr(target) > radiusSq;
    }

    private VillagerNavigationSupport() {}

    public static void tick(VillagerEntity villager, ServerWorld world, VillagerAgentData agent) {
        PathNavigator navigation = villager.getNavigation();
        if (!(navigation instanceof GroundPathNavigator)) return;
        GroundPathNavigator ground = (GroundPathNavigator) navigation;
        // Vanilla sets these in its constructor. Keep them explicit for managed villagers in case
        // another AI controller changed the node evaluator during a previous action.
        ground.setCanFloat(true);
        ground.setCanOpenDoors(true);
        ground.getNodeEvaluator().setCanPassDoors(true);
        // Reduce water avoidance penalty — vanilla villagers set WATER malus to 8.0 (heavily avoid).
        // Setting to 0 lets them path through shallow water (rivers, ponds, waterlogged blocks).
        villager.setPathfindingMalus(net.minecraft.pathfinding.PathNodeType.WATER, 0.0F);
        villager.setPathfindingMalus(net.minecraft.pathfinding.PathNodeType.WATER_BORDER, 0.0F);

        // Longer routes: vanilla villagers search 48 blocks / 768 nodes, so forests, ore and fields just
        // outside a village were "unreachable" and work was abandoned. Search 80 blocks / 2x nodes.
        net.minecraft.entity.ai.attributes.ModifiableAttributeInstance follow =
                villager.getAttribute(net.minecraft.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < LONG_FOLLOW_RANGE) follow.setBaseValue(LONG_FOLLOW_RANGE);
        ground.setMaxVisitedNodesMultiplier(2.0F);

        VillagerPathfinding.poll(villager, agent);
        VillagerPathfinding.stepAssist(villager, ground);
        swimAssist(villager, ground);
        BrainLog.movementObstacle(agent, villager, world);
        continuePartialPath(villager, agent, ground);
        recoverMovement(villager, world, agent, ground);
        Path path = ground.getPath();
        BlockPos nextDoor = null;
        if (path != null && !ground.isDone()) {
            int next = path.getNextNodeIndex();
            for (int i = next; i < Math.min(path.getNodeCount(), next + 3); i++) {
                BlockPos node = path.getNodePos(i);
                if (node.distSqr(villager.blockPosition()) > 9) continue;
                if (woodenDoor(world, node)) { nextDoor = node; break; }
                if (woodenDoor(world, node.below())) { nextDoor = node.below(); break; }
                if (woodenDoor(world, node.above())) { nextDoor = node.above(); break; }
            }
        }
        if (nextDoor != null) {
            BlockState state = world.getBlockState(nextDoor);
            if (!state.getValue(DoorBlock.OPEN)) {
                ((DoorBlock) state.getBlock()).setOpen(world, state, nextDoor, true);
                agent.brain().openedDoors.add(nextDoor.immutable());
                BrainLog.diagnostic(agent, "door-open", "pos=" + nextDoor + " pathNext="
                        + (path == null ? "none" : path.getNextNodeIndex()));
            }
        }
        Iterator<BlockPos> openedDoors = agent.brain().openedDoors.iterator();
        while (openedDoors.hasNext()) {
            BlockPos opened = openedDoors.next();
            if (!world.isLoaded(opened)) { openedDoors.remove(); continue; }
            if (villager.blockPosition().distSqr(opened) <= 9
                    || !world.getEntitiesOfClass(VillagerEntity.class, new AxisAlignedBB(opened).inflate(1.5)).isEmpty())
                continue;
            BlockState state = world.getBlockState(opened);
            if (state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state)
                    && state.getValue(DoorBlock.OPEN)) {
                ((DoorBlock) state.getBlock()).setOpen(world, state, opened, false);
                BrainLog.diagnostic(agent, "door-close", "pos=" + opened);
            }
            openedDoors.remove();
        }
    }

    /** Nodes left on a partial path when the continuation search starts (before the villager halts). */
    private static final int CONTINUE_AT_NODES = 6;

    /**
     * A long walk is a chain of partial vanilla paths. Instead of letting the villager reach the end of
     * one, stop, and only then search again, search the next leg off-thread while it is still walking.
     */
    private static void continuePartialPath(VillagerEntity v, VillagerAgentData a, GroundPathNavigator nav) {
        if (!VillagerPathfinding.asyncEnabled() || v.isSleeping() || VillagerPathfinding.pending(v)) return;
        VillagerAction action = a.getCurrentAction();
        if (action == null || action.hasNavigationFailed()
                || (action.getActionType() != VillagerAction.ActionType.MOVE
                    && action.getPhase() != VillagerAction.ActionPhase.WALKING)) return;
        Path path = nav.getPath();
        if (path == null || path.isDone() || path.canReach()) return;
        if (path.getNodeCount() - path.getNextNodeIndex() > CONTINUE_AT_NODES) return;
        BlockPos target = action.getStandCell() != null ? action.getStandCell() : action.getTargetBlockPos();
        if (target == null) target = path.getTarget();
        if (target == null) return;
        VillagerPathfinding.request(v, target, action.getStandCell() != null ? 0 : 1,
                VillagerPathfinding.lastSpeed(nav), action, "continue-partial");
    }

    private static void recoverMovement(VillagerEntity v, ServerWorld world, VillagerAgentData a, GroundPathNavigator nav) {
        VillagerAction action = a.getCurrentAction();
        BrainRuntime b = a.brain();
        if (action == null || !action.isGoalDriven() || v.isSleeping()
                || (action.getActionType() != VillagerAction.ActionType.MOVE
                && action.getPhase() != VillagerAction.ActionPhase.WALKING)) {
            b.navigationAction = null;
            return;
        }
        long now = world.getGameTime();
        int interval = com.github.AaronAA0721.villageragent.config.ModConfig.PATH_REPATH_INTERVAL.get();
        if (action != b.navigationAction) {
            b.navigationAction = action;
            b.navigationProgress = v.position();
            b.navigationProgressTick = now;
            b.nextNavigationRecovery = now + interval;
            b.navigationRecoveries = 0;
        }
        double dx = v.getX() - b.navigationProgress.x, dy = v.getY() - b.navigationProgress.y, dz = v.getZ() - b.navigationProgress.z;
        if (dx * dx + dy * dy + dz * dz >= 0.25) {
            b.navigationProgress = v.position(); b.navigationProgressTick = now;
            b.navigationRecoveries = 0;
        }
        BlockPos target = action.getStandCell() != null ? action.getStandCell() : action.getTargetBlockPos();
        if (target == null || action.hasNavigationFailed()) return;
        double reach = action.isFarmMove() ? 2 : action.isRestMove() ? 4 : action.getStandCell() != null ? 1 : 16;
        // Pressing against a block is a stall worth fixing at once; a slow walk is given the full interval.
        int wait = v.horizontalCollision ? Math.min(interval, COLLISION_STALL_TICKS) : interval;
        if (v.blockPosition().distSqr(target) <= reach || now - b.navigationProgressTick < wait
                || now < b.nextNavigationRecovery) return;
        b.nextNavigationRecovery = now + wait;
        Path old = nav.getPath();
        boolean assisted = false;
        // Assist only a nearby ascending path node with headroom; never jump blindly into walls/fences.
        if (old != null && !old.isDone() && v.isOnGround() && v.horizontalCollision) {
            BlockPos next = old.getNodePos(old.getNextNodeIndex());
            double rise = next.getY() - v.getY();
            double nx = next.getX() + 0.5 - v.getX(), nz = next.getZ() + 0.5 - v.getZ();
            if (rise > 0.5 && rise <= 1.25 && nx * nx + nz * nz <= 2.25
                    && world.noCollision(v, v.getBoundingBox().move(0, 1, 0))) {
                v.getJumpControl().jump();
                assisted = true;
                BrainLog.diagnostic(a, "[DEBUG-movement] jump-request", "source=stuck-recovery next=" + next + " rise=" + rise);
            }
        }
        // Wedged on a corner or edge (no step to jump): side-step into a free neighbouring cell first, so the
        // following re-plan starts from a clean position instead of reproducing the same blocked route.
        if (!assisted && v.isOnGround() && v.horizontalCollision && b.navigationRecoveries % 2 == 0) {
            BlockPos side = sidestepCell(world, v, target);
            if (side != null) {
                b.navigationRecoveries++;
                nav.moveTo(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, VillagerPathfinding.lastSpeed(nav));
                b.nextNavigationRecovery = now + 15;
                BrainLog.diagnostic(a, "navigation-sidestep", "from=" + v.blockPosition() + " to=" + side + " target=" + target);
                return;
            }
        }
        if (++b.navigationRecoveries > 5) {
            action.setNavigationFailed(true); nav.stop();
            BrainLog.diagnostic(a, "navigation-stuck", "target=" + target + " position=" + v.blockPosition());
            return;
        }
        if (VillagerPathfinding.asyncEnabled()) {
            // Keep the current (stalled) path until the off-thread route arrives; no stop/start.
            boolean queued = VillagerPathfinding.request(v, target, 0, VillagerPathfinding.lastSpeed(nav), action, "stuck-recovery");
            BrainLog.diagnostic(a, "navigation-repath", "target=" + target + " attempt=" + b.navigationRecoveries
                    + " async=" + queued);
            return;
        }
        // stop() invalidates the cached route before searching against the current blocks.
        double speed = VillagerPathfinding.lastSpeed(nav);
        nav.stop();
        Path fresh = nav.createPath(target, 0);
        boolean started = fresh != null && nav.moveTo(fresh, speed);
        BrainLog.diagnostic(a, "navigation-repath", "target=" + target + " attempt=" + b.navigationRecoveries
                + " started=" + started);
    }

    /** Ticks pressed against a block (no progress) before stuck recovery acts. */
    private static final int COLLISION_STALL_TICKS = 30;

    /**
     * Buoyancy + speed boost + shore jump for villagers in water.
     * Vanilla villagers have almost no active swim ability: SwimTask only fires when 40% submerged,
     * and even then provides no upward thrust. This helper runs every tick and:
     * <ol>
     *   <li>Applies configurable upward velocity so the villager surfaces instead of bobbing at eye level.</li>
     *   <li>Boosts navigation speed to counter water drag and current push.</li>
     *   <li>Triggers a jump when pressed against a shore block, even though isOnGround() is false in water.</li>
     * </ol>
     */
    private static void swimAssist(VillagerEntity v, GroundPathNavigator nav) {
        if (!v.isInWater()) return;

        // ── 1. Buoyancy: push the villager upward so it surfaces ──
        double buoyancy = com.github.AaronAA0721.villageragent.config.ModConfig.SWIM_BUOYANCY.get();
        if (buoyancy > 0) {
            net.minecraft.util.math.vector.Vector3d motion = v.getDeltaMovement();
            // Only boost when below the water surface (eyes submerged or sinking).
            // Once the villager is at surface level, let vanilla float logic maintain it.
            if (v.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0.0
                    || motion.y < 0) {
                v.setDeltaMovement(motion.x, motion.y + buoyancy, motion.z);
            }
        }

        // ── 2. Speed boost: counter drag so the villager can fight currents ──
        // Do NOT multiply the current velocity — that compounds every tick and launches the
        // villager like a rocket. Instead add a small, capped horizontal impulse toward the
        // intended direction of travel, which only offsets water drag / current push.
        double speedMult = com.github.AaronAA0721.villageragent.config.ModConfig.SWIM_SPEED_BOOST.get();
        if (speedMult > 1.0) {
            net.minecraft.util.math.vector.Vector3d motion = v.getDeltaMovement();
            double horiz = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            // Cap horizontal swim speed at a modest ceiling so it never exceeds a brisk walk.
            double cap = 0.35;
            double extra = (speedMult - 1.0) * 0.05; // at 1.6 -> +0.03 per tick, gentle
            if (horiz < cap) {
                // Push along the current heading; if barely moving, fall back to the look/yaw direction.
                double dx = motion.x, dz = motion.z;
                if (horiz < 1e-3) {
                    float yaw = v.yRot * ((float) Math.PI / 180f);
                    dx = -Math.sin(yaw); dz = Math.cos(yaw);
                } else {
                    dx /= horiz; dz /= horiz;
                }
                double impulse = Math.min(extra, cap - horiz);
                v.setDeltaMovement(motion.x + dx * impulse, motion.y, motion.z + dz * impulse);
            }
        }

        // ── 3. Shore jump: climb out when pressed against a block at water's edge ──
        if (v.horizontalCollision) {
            // In water isOnGround() is false, so the normal step/jump assist never fires.
            // Trigger a jump if the next path node is above us and headroom is clear.
            net.minecraft.pathfinding.Path path = nav.getPath();
            if (path != null && !path.isDone()) {
                BlockPos next = path.getNodePos(path.getNextNodeIndex());
                double rise = next.getY() - v.getY();
                if (rise > 0.1 && rise <= 1.5
                        && v.level.noCollision(v, v.getBoundingBox().move(0, 1, 0))) {
                    v.getJumpControl().jump();
                }
            } else {
                // No path but colliding — just try to jump out
                if (v.level.noCollision(v, v.getBoundingBox().move(0, 1, 0))) {
                    v.getJumpControl().jump();
                }
            }
        }
    }

    /** A free, standable horizontal neighbour (feet and head clear, solid floor), preferring cells nearer the target. */
    private static BlockPos sidestepCell(ServerWorld world, VillagerEntity v, BlockPos target) {
        BlockPos here = v.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.util.Direction dir : net.minecraft.util.Direction.Plane.HORIZONTAL) {
            BlockPos cell = here.relative(dir);
            if (!world.isLoaded(cell)) continue;
            if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()
                    || !world.getBlockState(cell.above()).getCollisionShape(world, cell.above()).isEmpty()) continue;
            BlockPos floor = cell.below();
            if (!world.getBlockState(floor).isFaceSturdy(world, floor, net.minecraft.util.Direction.UP)) continue;
            if (!world.getFluidState(cell).isEmpty()) continue;
            double d = cell.distSqr(target);
            if (d < bestDist) { bestDist = d; best = cell; }
        }
        return best;
    }

    private static boolean woodenDoor(ServerWorld world, BlockPos pos) {
        if (!world.isLoaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state);
    }
}
