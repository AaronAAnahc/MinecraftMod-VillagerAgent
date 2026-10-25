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
        if (action != b.navigationAction) {
            b.navigationAction = action;
            b.navigationProgress = v.position();
            b.navigationProgressTick = now;
            b.nextNavigationRecovery = now + 40;
            b.navigationRecoveries = 0;
        }
        double dx = v.getX() - b.navigationProgress.x, dz = v.getZ() - b.navigationProgress.z;
        if (dx * dx + dz * dz >= 0.25) {
            b.navigationProgress = v.position(); b.navigationProgressTick = now;
            b.navigationRecoveries = 0;
        }
        BlockPos target = action.getStandCell() != null ? action.getStandCell() : action.getTargetBlockPos();
        if (target == null || action.hasNavigationFailed()) return;
        double reach = action.isFarmMove() ? 2 : action.isRestMove() ? 4 : action.getStandCell() != null ? 1 : 16;
        if (v.blockPosition().distSqr(target) <= reach || now - b.navigationProgressTick < 40
                || now < b.nextNavigationRecovery) return;
        b.nextNavigationRecovery = now + 40;
        Path old = nav.getPath();
        // Assist only a nearby ascending path node with headroom; never jump blindly into walls/fences.
        if (old != null && !old.isDone() && v.isOnGround() && v.horizontalCollision) {
            BlockPos next = old.getNodePos(old.getNextNodeIndex());
            double rise = next.getY() - v.getY();
            double nx = next.getX() + 0.5 - v.getX(), nz = next.getZ() + 0.5 - v.getZ();
            if (rise > 0.5 && rise <= 1.25 && nx * nx + nz * nz <= 2.25
                    && world.noCollision(v, v.getBoundingBox().move(0, 1, 0))) v.getJumpControl().jump();
        }
        if (++b.navigationRecoveries > 3) {
            action.setNavigationFailed(true); nav.stop();
            BrainLog.diagnostic(a, "navigation-stuck", "target=" + target + " position=" + v.blockPosition());
            return;
        }
        // stop() invalidates the cached route before searching against the current blocks.
        nav.stop();
        Path fresh = nav.createPath(target, 0);
        boolean started = fresh != null && nav.moveTo(fresh, WALK_SPEED);
        BrainLog.diagnostic(a, "navigation-repath", "target=" + target + " attempt=" + b.navigationRecoveries
                + " started=" + started);
    }

    private static boolean woodenDoor(ServerWorld world, BlockPos pos) {
        if (!world.isLoaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state);
    }
}
