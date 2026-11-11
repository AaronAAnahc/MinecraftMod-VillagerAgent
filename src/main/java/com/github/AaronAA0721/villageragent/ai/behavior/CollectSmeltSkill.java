package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/**
 * Collects finished smelting products from nearby furnaces / blast furnaces.
 *
 * <p>The scan radius matches {@link SmeltSkill#TETHER_RADIUS} so that a tethered
 * villager waiting for a smelt to finish will detect the output the moment it's done.
 *
 * <p>Flow: detect furnace with output in slot 2 → walk to it → extract items into
 * villager inventory → gain SMITHING XP → SUCCESS.
 */
public final class CollectSmeltSkill implements VillagerSkill {

    /** Must match SmeltSkill.TETHER_RADIUS so tethered detection range equals wait range. */
    public static final int SCAN_RADIUS = SmeltSkill.TETHER_RADIUS;

    private static final class State {
        BlockPos furnace;
    }

    @Override public String id() { return "collect_smelt"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.SMITHING; }
    @Override public int minLevel() { return 1; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "collect_smelt".equals(g.getGoalType()) && passesCapabilityGate(a);
    }

    /**
     * High chance when a nearby furnace has output ready; zero otherwise.
     * This makes the goal auto-activate right when a smelt finishes.
     */
    @Override
    public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        // We can't access the world from here (only agent + goal), so we use a cached
        // flag set by the tether system. When tethered, the smelt is expected to finish
        // around tetherReadyTick. After that tick, boost this goal.
        if (a.brain().isTethered() && now < a.brain().tetherReadyTick) return 0.1;
        // A loaded batch is due: collect promptly. Without one, only look occasionally (e.g. after a reload).
        return a.brain().isTethered() ? 1.5 : 0.2;
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);

        // ── Find furnace with output ──
        if (s.furnace == null) {
            s.furnace = SmeltSkill.findFurnaceWithOutput(a, v, w, SCAN_RADIUS);
            if (s.furnace == null) {
                // Nothing to collect is not a failure: look again later without counting a failed attempt.
                g.setMinimumExitCooldownTicks(600);
                BrainLog.diagnostic(a, "collect-smelt-idle", "reason=no_furnace_with_output radius=" + SCAN_RADIUS);
                return SkillResult.WAITING;
            }
        }

        g.setMinimumExitCooldownTicks(0); // A furnace with output was found: normal pacing.
        // Verify the furnace still has output.
        TileEntity te = w.getBlockEntity(s.furnace);
        if (!(te instanceof AbstractFurnaceTileEntity)
                || ((AbstractFurnaceTileEntity) te).getItem(2).isEmpty()) {
            BrainLog.diagnostic(a, "collect-smelt-idle", "reason=output_gone furnace=" + s.furnace);
            s.furnace = null;
            g.setMinimumExitCooldownTicks(600);
            return SkillResult.WAITING;
        }

        // ── Walk to furnace ──
        if (v.blockPosition().distSqr(s.furnace) > 4) {
            BlockPos stand = ReachablePaths.standingBeside(w, v, s.furnace);
            if (stand == null) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(String.valueOf(s.furnace)));
                s.furnace = null; return SkillResult.FAIL;
            }
            SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to furnace to collect", g.getPriority());
                move.setTargetLocation(stand);
                move.setArrivalDistanceSq(0.5);
                return move;
            });
            if (r == SkillResult.RUNNING) return r;
            if (r != SkillResult.SUCCESS) {
                g.setLastFailure(FailureReason.UNREACHABLE.with(String.valueOf(s.furnace)));
                s.furnace = null; return SkillResult.FAIL;
            }
        }

        // ── Collect from output slot ──
        te = w.getBlockEntity(s.furnace);
        if (!(te instanceof AbstractFurnaceTileEntity)) {
            g.setLastFailure(FailureReason.UNKNOWN.with("furnace removed"));
            s.furnace = null; return SkillResult.FAIL;
        }
        AbstractFurnaceTileEntity furnace = (AbstractFurnaceTileEntity) te;
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            BrainLog.diagnostic(a, "collect-smelt-idle", "reason=output_taken furnace=" + s.furnace);
            s.furnace = null; g.setMinimumExitCooldownTicks(600); return SkillResult.WAITING;
        }

        ItemStack taken = output.copy();
        int moved = FurnaceInventory.collect(a.getInventory(), furnace);
        if (moved == 0) {
            g.setLastFailure(FailureReason.UNKNOWN.with("no inventory space for " + output.getItem().getRegistryName()));
            s.furnace = null; return SkillResult.FAIL;
        }
        taken.setCount(moved);

        v.swing(Hand.MAIN_HAND);
        w.playSound(null, s.furnace.getX(), s.furnace.getY(), s.furnace.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundCategory.NEUTRAL, 0.5f, 1.2f);

        a.gainCapabilityXp(CapabilityId.SMITHING,
                com.github.AaronAA0721.villageragent.config.ModConfig.CAPABILITY_PRACTICE_XP.get(), v, w.getGameTime());
        a.addMemory("Collected " + taken.getCount() + " " + taken.getItem().getRegistryName().getPath().replace('_', ' ')
                + " from furnace");

        // Clear the tether if we were waiting for this furnace.
        if (a.brain().isTethered() && s.furnace.equals(a.brain().tetherPos)
                && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty()) {
            a.brain().clearTether();
        }

        g.markProgress();
        return SkillResult.SUCCESS;
    }
}
