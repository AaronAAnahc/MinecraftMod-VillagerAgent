package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.tags.FluidTags;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Atomic world-interaction layer: placing and breaking a SINGLE block.
 *
 * <p>Both actions obey the "within 1 block" rule — the villager must stand in a legal
 * cell whose Chebyshev distance to the target block is &le; 1 (the 26-neighbourhood),
 * mirroring {@link FarmingAction}'s no-raycast, walk-then-act pattern. Breaking uses
 * the tool the villager algorithmically equips ({@link VillagerEquipmentHelper}), and
 * its per-tick progress matches the player exactly via
 * {@link VillagerEquipmentHelper#computeBreakTicks}.
 */
public class BlockInteractionAction {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Walk/act speed for world interaction (same ballpark as farming). */
    private static final double WALK_SPEED = VillagerNavigationSupport.WALK_SPEED;

    public enum StepResult {
        /** Block was placed/broken (or found already done) — advance to the next step. */
        DONE,
        /** Still walking or still breaking — keep this action next tick. */
        IN_PROGRESS,
        /** Could not be done (no standing cell, missing block, unbreakable, stuck). */
        ABANDONED
    }

    /** All 26 neighbours of a cell (excluding the cell itself). */
    private static final List<BlockPos> NEIGHBOURS = new ArrayList<>();
    static {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NEIGHBOURS.add(new BlockPos(dx, dy, dz));
    }

    // ── Reusable helpers (used by the planner too) ──────────────────────────

    /** True if a villager could occupy {@code a} (feet) and have headroom + a solid, dry floor. */
    public static boolean isStandable(ServerWorld world, BlockPos a) {
        if (!world.isEmptyBlock(a)) return false;            // feet must be air
        if (!world.isEmptyBlock(a.above())) return false;    // head must be air
        BlockPos floor = a.below();
        BlockState floorState = world.getBlockState(floor);
        if (world.isEmptyBlock(floor)) return false;         // need a floor
        if (floorState.getMaterial().isLiquid() || !floorState.getMaterial().isSolid()) return false;
        // The cell itself (and the head cell) must be dry
        if (world.getFluidState(a).is(FluidTags.WATER)) return false;
        if (world.getFluidState(a).is(FluidTags.LAVA)) return false;
        if (world.getFluidState(a.above()).is(FluidTags.WATER)) return false;
        if (world.getFluidState(a.above()).is(FluidTags.LAVA)) return false;
        return true;
    }

    /**
     * Find a legal standing cell from which the villager can reach {@code target}
     * (Chebyshev &le; 1). Prefers a cell whose floor is an existing world block
     * (more stable) but accepts any standable cell. Returns null if none exists.
     */
    public static BlockPos findStandable(ServerWorld world, BlockPos target) {
        BlockPos best = null;
        for (BlockPos off : NEIGHBOURS) {
            BlockPos a = target.offset(off.getX(), off.getY(), off.getZ());
            if (!isStandable(world, a)) continue;
            // Prefer a floor that is already a real (non-air) world block — i.e. not just
            // another to-be-placed block. We can't know the planned set here, so accept any.
            if (best == null) best = a;
        }
        return best;
    }

    /** Chebyshev distance &le; 1 — the precise "within one block" rule. */
    public static boolean withinReach(BlockPos from, BlockPos to) {
        return Math.max(Math.abs(from.getX() - to.getX()),
                Math.max(Math.abs(from.getY() - to.getY()),
                        Math.abs(from.getZ() - to.getZ()))) <= 1;
    }

    // ── Place ──────────────────────────────────────────────────────────────

    /**
     * Tick a PLACE action one step. Assumes {@code action.getTargetBlockPos()} is set and
     * {@code action.getPlaceBlock()} holds the block to place.
     */
    public static StepResult tickPlace(VillagerEntity villager, ServerWorld world,
                                       VillagerAgentData agent, VillagerAction action) {
        BlockPos target = action.getTargetBlockPos();
        BlockPos stand = action.getStandCell();
        if (stand == null) stand = findStandable(world, target);
        if (stand == null) {
            agent.addMemory("Couldn't find a place to stand to place a block at " + target);
            LOGGER.debug(agent.getName() + " no standing cell for placing at " + target);
            return StepResult.ABANDONED;
        }
        action.setStandCell(stand);

        BlockPos vPos = villager.blockPosition();

        if (action.getPhase() != VillagerAction.ActionPhase.ACTING) {
            if (withinReach(vPos, target)) {
                action.setPhase(VillagerAction.ActionPhase.ACTING);
                villager.getNavigation().stop();
            } else {
                action.incrementStuckTicks();
                if (action.getStuckTicks() > ModConfig.BUILD_STUCK_TIMEOUT.get()) {
                    LOGGER.debug(agent.getName() + " gave up reaching place-stand " + stand);
                    return StepResult.ABANDONED;
                }
                if (action.getStuckTicks() % 20 == 0) {
                    villager.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, WALK_SPEED);
                }
                return StepResult.IN_PROGRESS;
            }
        }

        // ── ACTING: place the block ──
        Block block = action.getPlaceBlock();
        if (block == null) return StepResult.ABANDONED;

        if (!world.isEmptyBlock(target)) {
            // Already occupied (placed by someone else, or never empty) — treat as done/skip.
            agent.addMemory("Target " + target + " already filled, skipped placing");
            return StepResult.DONE;
        }

        ItemStack need = new ItemStack(block, 1);
        if (agent.getInventory().countItem(need) <= 0) {
            agent.addMemory("No " + block.getRegistryName() + " left to place at " + target);
            LOGGER.debug(agent.getName() + " out of " + block.getRegistryName());
            return StepResult.ABANDONED;
        }

        ItemStack material = ItemStack.EMPTY;
        for (ItemStack stack : agent.getInventory().getItems()) {
            if (!stack.isEmpty() && stack.getItem() == need.getItem()) { material = stack; break; }
        }
        if (material.isEmpty()) return StepResult.ABANDONED;
        net.minecraftforge.common.util.FakePlayer actor = net.minecraftforge.common.util.FakePlayerFactory.get(
                world, new com.mojang.authlib.GameProfile(villager.getUUID(), "[VillagerAgent]"));
        actor.absMoveTo(villager.getX(), villager.getY(), villager.getZ(), villager.yRot, villager.xRot);
        actor.setItemSlot(net.minecraft.inventory.EquipmentSlotType.MAINHAND, material);
        net.minecraft.util.Direction face = net.minecraft.util.Direction.UP;
        BlockPos support = target.below();
        for (net.minecraft.util.Direction direction : net.minecraft.util.Direction.values()) {
            BlockPos adjacent = target.relative(direction);
            if (!world.isEmptyBlock(adjacent)) { support = adjacent; face = direction.getOpposite(); break; }
        }
        net.minecraft.util.math.BlockRayTraceResult hit = new net.minecraft.util.math.BlockRayTraceResult(
                net.minecraft.util.math.vector.Vector3d.atCenterOf(support), face, support, false);
        net.minecraft.util.ActionResultType result = net.minecraftforge.common.ForgeHooks.onPlaceItemIntoWorld(
                new net.minecraft.item.ItemUseContext(actor, net.minecraft.util.Hand.MAIN_HAND, hit));
        if (!result.consumesAction() || world.getBlockState(target).getBlock() != block) return StepResult.ABANDONED;
        villager.swing(net.minecraft.util.Hand.MAIN_HAND);
        agent.addMemory("Placed " + block.getRegistryName() + " at " + target);
        WorldStructureIndex.instance(world).onBlockChanged(target);
        LOGGER.debug(agent.getName() + " placed " + block.getRegistryName() + " at " + target);
        return StepResult.DONE;
    }

    // ── Break ──────────────────────────────────────────────────────────────

    /**
     * Tick a BREAK action one step. Uses tool-aware, player-accurate breaking speed.
     * Runs every tick while the action is active (the manager must not throttle it).
     */
    public static StepResult tickBreak(VillagerEntity villager, ServerWorld world,
                                       VillagerAgentData agent, VillagerAction action) {
        BlockPos target = action.getTargetBlockPos();
        BlockPos stand = action.getStandCell();
        if (stand == null) stand = findStandable(world, target);
        if (stand == null) {
            agent.addMemory("Couldn't find a place to stand to break the block at " + target);
            return StepResult.ABANDONED;
        }
        action.setStandCell(stand);

        BlockPos vPos = villager.blockPosition();

        if (action.getPhase() != VillagerAction.ActionPhase.ACTING) {
            if (withinReach(vPos, target)) {
                action.setPhase(VillagerAction.ActionPhase.ACTING);
                villager.getNavigation().stop();
            } else {
                action.incrementStuckTicks();
                if (action.getStuckTicks() > ModConfig.BUILD_STUCK_TIMEOUT.get()) {
                    LOGGER.debug(agent.getName() + " gave up reaching break-stand " + stand);
                    return StepResult.ABANDONED;
                }
                if (action.getStuckTicks() % 20 == 0) {
                    villager.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, WALK_SPEED);
                }
                return StepResult.IN_PROGRESS;
            }
        }

        if (!withinReach(villager.blockPosition(), target)) {
            world.destroyBlockProgress(villager.getId(), target, -1);
            action.setBreakTargetTicks(0);
            action.setPhase(VillagerAction.ActionPhase.WALKING);
            return StepResult.IN_PROGRESS;
        }
        // ── ACTING: break the block (tool-aware, player-accurate) ──
        BlockState state = world.getBlockState(target);
        if (world.isEmptyBlock(target)) return StepResult.DONE; // already gone

        if (state.getDestroySpeed(world, target) < 0.0f) {
            agent.addMemory("The block at " + target + " is unbreakable — left it alone");
            return StepResult.ABANDONED;
        }

        if (action.getBreakingState() != null && !state.equals(action.getBreakingState())) {
            world.destroyBlockProgress(villager.getId(), target, -1);
            action.setBreakTargetTicks(0);
        }
        // Initialise break timing on first ACTING tick
        if (action.getBreakTargetTicks() <= 0) {
            ItemStack tool = VillagerEquipmentHelper.equipBestToolForBlock(villager, agent, state);
            if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) return StepResult.ABANDONED;
            action.setBreakingState(state);
            action.setBreakTool(tool);
            int ticks = VillagerEquipmentHelper.computeBreakTicks(world, target, state, tool);
            if (ticks == Integer.MAX_VALUE) return StepResult.ABANDONED;
            action.setBreakTargetTicks(ticks);
            action.setBreakProgress(0);
            LOGGER.debug(agent.getName() + " breaking " + state.getBlock().getRegistryName()
                    + " at " + target + " in ~" + ticks + " ticks");
        }

        ItemStack tool = action.getBreakTool();
        if (!tool.isEmpty() && !agent.getInventory().getItems().contains(tool)) return StepResult.ABANDONED;
        if (state.requiresCorrectToolForDrops() && (tool.isEmpty() || !tool.isCorrectToolForDrops(state))) return StepResult.ABANDONED;
        action.incrementBreakProgress();
        if (action.getBreakProgress() % 5 == 0) villager.swing(net.minecraft.util.Hand.MAIN_HAND);
        int stage = Math.min(9, action.getBreakProgress() * 10 / Math.max(1, action.getBreakTargetTicks()));
        world.destroyBlockProgress(villager.getId(), target, stage);
        villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (action.getBreakProgress() >= action.getBreakTargetTicks()) {
            net.minecraftforge.common.util.FakePlayer actor = net.minecraftforge.common.util.FakePlayerFactory.get(
                    world, new com.mojang.authlib.GameProfile(villager.getUUID(), "[VillagerAgent]"));
            actor.setPos(villager.getX(), villager.getY(), villager.getZ());
            actor.setItemSlot(net.minecraft.inventory.EquipmentSlotType.MAINHAND, tool);
            int xp = net.minecraftforge.common.ForgeHooks.onBlockBreakEvent(world, net.minecraft.world.GameType.SURVIVAL, actor, target);
            world.destroyBlockProgress(villager.getId(), target, -1);
            if (xp < 0) return StepResult.ABANDONED;
            java.util.List<ItemStack> drops = Block.getDrops(state, world, target, world.getBlockEntity(target), actor, tool);
            if (!world.destroyBlock(target, false)) return StepResult.ABANDONED;
            for (ItemStack drop : drops) Block.popResource(world, target, drop);
            tool.mineBlock(world, state, target, actor);
            villager.setItemSlot(net.minecraft.inventory.EquipmentSlotType.MAINHAND, tool.copy());
            if (xp > 0) world.addFreshEntity(new net.minecraft.entity.item.ExperienceOrbEntity(world,
                    target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, xp));
            agent.addMemory("Broke " + state.getBlock().getRegistryName() + " at " + target);
            WorldStructureIndex.instance(world).onBlockChanged(target);
            LOGGER.debug(agent.getName() + " broke " + state.getBlock().getRegistryName() + " at " + target);
            return StepResult.DONE;
        }
        return StepResult.IN_PROGRESS;
    }
}
