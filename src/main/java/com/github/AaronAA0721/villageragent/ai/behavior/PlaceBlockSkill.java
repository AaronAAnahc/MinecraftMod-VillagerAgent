package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SoundType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.DirectionalPlaceContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Place a block from inventory at a specified target position: walk into reach, then place.
 *
 * <p>Goal contract:
 * <pre>
 *   goalType       = "place_block"
 *   targetItem     = registry id of the block item (e.g. "minecraft:crafting_table")
 *   targetLocation = the exact BlockPos to place at
 * </pre>
 *
 * <p>Every tick, before doing anything, the pre-conditions are re-checked:
 * <ol>
 *   <li>the item is a real block item — otherwise DELETE (the goal can never succeed);</li>
 *   <li>the block is already there — SUCCESS (idempotent);</li>
 *   <li>the villager holds the block — otherwise BLOCKED + MISSING_ITEM, which makes
 *       {@code FailureRecoveryPlanner} queue an {@code acquire_item}; this goal waits for it and resumes;</li>
 *   <li>the target cell is replaceable and the block can survive there — otherwise DELETE;</li>
 *   <li>out of reach — pick the cheapest <em>reachable</em> standing cell next to the target
 *       (complete A* path, {@link ReachablePaths}) and walk there as a {@code move} child goal.
 *       No reachable cell → FAIL + UNREACHABLE (bounded retries via {@code GoalExitPolicy}).</li>
 * </ol>
 *
 * <p>Walking and placing are one root goal, so {@link GoalCommitment} keeps the villager on it:
 * ordinary work (farming, logging…) cannot take over on the way; only emergencies (priority 9+),
 * exhaustion and curfew sleep can. {@link PlaceNearSelfSkill} reuses this as its child.
 */
public final class PlaceBlockSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("PlaceBlockSkill");

    /** Player-like reach, measured from the eyes to the target block centre. */
    private static final double REACH = 4.5;
    /** Ticks to wait for another entity to step out of the target cell before backing off. */
    private static final int OBSTRUCTED_WAIT_TICKS = 60;

    /** Per-execution scratch state, stored on this goal's task frame (dropped when the task ends). */
    private static final class State {
        BlockPos stand;
        boolean walked;
        long obstructedSince = Long.MIN_VALUE;
    }

    @Override public String id() { return "place_block"; }
    @Override public boolean isPrimitive() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "place_block".equals(g.getGoalType()) && g.getTargetItem() != null && g.getTargetLocation() != null;
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        String itemId = g.getTargetItem();
        BlockPos target = g.getTargetLocation();
        State st = a.brain().tasks.current().state(State.class, State::new);

        // ── 1. Item must be a block item ──
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (!(item instanceof BlockItem)) {
            LOG.warn("{} place_block: {} is not a block item", a.getName(), itemId);
            a.addMemory("Can't place " + itemId + ": it isn't a block");
            return SkillResult.DELETE;
        }
        Block block = ((BlockItem) item).getBlock();

        // ── 2. Target sanity ──
        if (target.getY() < 0 || target.getY() > 255 || !w.isLoaded(target)) {
            g.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
            return SkillResult.FAIL;
        }
        BlockState existing = w.getBlockState(target);
        if (existing.getBlock() == block) {
            a.addMemory("There is already " + itemId + " at " + fmt(target));
            g.setCompleted(true);
            return SkillResult.SUCCESS;
        }

        // ── 3. Must hold the block (missing → acquire_item recovery, then resume) ──
        ItemStack held = findStack(a, item);
        if (held.isEmpty()) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with(itemId));
            return SkillResult.BLOCKED;
        }

        // ── 4. Spot must accept the block ──
        Direction facing = horizontalFacing(v, target);
        DirectionalPlaceContext ctx = new DirectionalPlaceContext(w, target, facing, held, Direction.UP);
        if (!existing.canBeReplaced(ctx)) {
            a.addMemory("Couldn't place " + itemId + " at " + fmt(target) + ": the spot is taken");
            return SkillResult.DELETE;
        }
        BlockState state = block.getStateForPlacement(ctx);
        if (state == null) state = block.defaultBlockState();
        if (!state.canSurvive(w, target)) {
            a.addMemory("Couldn't place " + itemId + " at " + fmt(target) + ": nothing to hold it up");
            return SkillResult.DELETE;
        }

        // ── 5. Get within reach and out of the target cell (walk = move child goal) ──
        if (!inReach(v, target) || occupiesTarget(v, target)) {
            if (st.walked) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                return SkillResult.FAIL;
            }
            if (st.stand == null) {
                st.stand = reachableStand(w, v, target);
                if (st.stand == null) {
                    a.addMemory("Couldn't find a way to reach " + fmt(target) + " to place " + itemId);
                    g.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                    return SkillResult.FAIL;
                }
                BrainLog.diagnostic(a, "place-walk", "item=" + itemId + " target=" + target + " stand=" + st.stand);
            }
            final BlockPos stand = st.stand;
            SkillResult walk = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to place " + itemId, g.getPriority());
                move.setTargetLocation(stand);
                move.setArrivalDistanceSq(1.0);
                move.setSource(g.getSource());
                return move;
            });
            if (walk == SkillResult.RUNNING) return SkillResult.RUNNING;
            if (walk != SkillResult.SUCCESS) return walk; // child's UNREACHABLE failure is copied to g
            st.walked = true;
            if (!inReach(v, target) || occupiesTarget(v, target)) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(target.toString()));
                return SkillResult.FAIL;
            }
        }

        // ── 6. Someone else standing in the cell: wait briefly, then back off ──
        v.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (!w.isUnobstructed(state, target, ISelectionContext.empty())) {
            long now = w.getGameTime();
            if (st.obstructedSince == Long.MIN_VALUE) st.obstructedSince = now;
            if (now - st.obstructedSince < OBSTRUCTED_WAIT_TICKS) return SkillResult.RUNNING;
            return SkillResult.WAITING; // retried after cooldown; not counted as a failure
        }

        // ── 7. Place ──
        w.setBlock(target, state, 3);
        block.setPlacedBy(w, target, state, v, held);
        SoundType sound = state.getSoundType();
        w.playSound(null, target, sound.getPlaceSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        held.shrink(1);
        v.swing(Hand.MAIN_HAND);

        LOG.info("{} placed {} at {}", a.getName(), itemId, target);
        a.addMemory("Placed " + itemId + " at " + fmt(target));
        g.markProgress();
        g.setCompleted(true);
        return SkillResult.SUCCESS;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ItemStack findStack(VillagerAgentData a, Item item) {
        for (ItemStack s : a.getInventory().getItems()) {
            if (!s.isEmpty() && s.getItem() == item) return s;
        }
        return ItemStack.EMPTY;
    }

    static boolean inReach(VillagerEntity v, BlockPos target) {
        double dx = v.getX() - (target.getX() + 0.5);
        double dy = v.getEyeY() - (target.getY() + 0.5);
        double dz = v.getZ() - (target.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private static boolean occupiesTarget(VillagerEntity v, BlockPos target) {
        return v.getBoundingBox().intersects(new AxisAlignedBB(target));
    }

    /**
     * Cheapest standable cell around the target with a complete path from the villager. Cells whose feet or
     * head would occupy the target are skipped (the villager would block its own placement).
     */
    static BlockPos reachableStand(ServerWorld w, VillagerEntity v, BlockPos target) {
        BlockPos best = null;
        double cost = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -2; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos stand = target.offset(dx, dy, dz);
            if (stand.equals(target) || stand.above().equals(target)) continue;
            if (!w.isLoaded(stand) || !BlockInteractionAction.isStandable(w, stand)) continue;
            double c = ReachablePaths.distance(v, stand);
            if (c < cost) { cost = c; best = stand.immutable(); }
        }
        return best;
    }

    private static Direction horizontalFacing(VillagerEntity v, BlockPos target) {
        double dx = (target.getX() + 0.5) - v.getX();
        double dz = (target.getZ() + 0.5) - v.getZ();
        if (Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3) return v.getDirection();
        return Direction.getNearest(dx, 0, dz);
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }
}
