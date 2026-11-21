package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Guard routine ({@code guard_work}): one patrol round through the village.
 *
 * <p>The route visits the notice board, the four edges of the village and a couple of houses (beds), in a
 * shuffled order so two guards do not walk in lock-step. At each stop the guard stands for a few seconds and
 * looks around with its sword drawn. The actual fighting is not done here: the combat tick
 * ({@code VillagerAgentManager.tickCombat} + {@link GuardCombat}) suspends this goal the moment a monster shows
 * up within {@code guard_scan_range} and resumes the patrol afterwards. A finished round is a little COMBAT practice.
 *
 * <p>No sword → BLOCKED with MISSING_ITEM("sword"): failure recovery then acquires one (weaponsmith, notice board,
 * or crafting a stone sword).
 */
public final class GuardWorkSkill implements VillagerSkill {

    private static final Logger LOG = LogManager.getLogger("GuardWorkSkill");
    /** Ticks a guard stands watch at each stop. */
    private static final int WATCH_TICKS = 80;
    /** Stops per round (board + edges + houses), before shuffling. */
    private static final int MAX_STOPS = 6;

    private static final class State {
        List<BlockPos> route;
        int index;
        long watchUntil = Long.MIN_VALUE;
        int visited;
    }

    @Override public String id() { return "guard_work"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.COMBAT; }
    @Override public int minLevel() { return 1; }

    @Override
    public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "guard_work".equals(g.getGoalType()) && GuardCombat.isGuard(a) && passesCapabilityGate(a);
    }

    @Override
    public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();

        ItemStack sword = findSword(a);
        if (sword.isEmpty()) {
            g.setLastFailure(FailureReason.MISSING_ITEM.with("sword"));
            return SkillResult.BLOCKED;
        }

        if (s.route == null) {
            s.route = buildRoute(a, v, w);
            if (s.route.isEmpty()) {
                g.setLastFailure(FailureReason.UNREACHABLE.with("no patrol route"));
                g.setMinimumExitCooldownTicks(1200);
                return SkillResult.FAIL;
            }
            g.setMinimumExitCooldownTicks(0);
            a.setCurrentActivity("patrolling");
        }
        if (s.index >= s.route.size()) return finishRound(a, g, v, w, s);

        BlockPos stop = s.route.get(s.index);

        // ── Stand watch at the stop ──
        if (s.watchUntil != Long.MIN_VALUE) {
            v.setItemSlot(EquipmentSlotType.MAINHAND, sword.copy());
            if ((now / 20) % 2 == 0) lookAround(v, now);
            a.brain().goalStartedTick = now;
            if (now < s.watchUntil) return SkillResult.RUNNING;
            s.watchUntil = Long.MIN_VALUE;
            s.index++;
            s.visited++;
            g.markProgress();
            // Between two stops other urgent work (eating, a trade meeting) may take over.
            GoalCommitment.workUnitDone(a, now);
            return SkillResult.RUNNING;
        }

        // ── Walk to the next stop ──
        v.setItemSlot(EquipmentSlotType.MAINHAND, sword.copy());
        SkillResult walk = BehaviorExecutor.executeChild(a, v, w, () -> {
            AgentGoal move = new AgentGoal("move", "Patrol to " + stop.getX() + "," + stop.getZ(), g.getPriority());
            move.setTargetLocation(stop);
            move.setArrivalDistanceSq(4.0);
            move.setSource(g.getSource());
            return move;
        });
        if (walk == SkillResult.RUNNING) return SkillResult.RUNNING;
        if (walk != SkillResult.SUCCESS) {
            // Unreachable stop: skip it rather than end the round.
            BrainLog.diagnostic(a, "patrol-stop-skipped", "stop=" + stop + " result=" + walk);
            s.index++;
            return SkillResult.RUNNING;
        }
        s.watchUntil = now + WATCH_TICKS + v.getRandom().nextInt(40);
        return SkillResult.RUNNING;
    }

    private static SkillResult finishRound(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s) {
        v.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        a.setCurrentActivity("idle");
        if (s.visited == 0) {
            g.setLastFailure(FailureReason.UNREACHABLE.with("every patrol stop"));
            g.setMinimumExitCooldownTicks(1200);
            return SkillResult.FAIL;
        }
        a.gainCapabilityXp(CapabilityId.COMBAT, Math.max(1, ModConfig.CAPABILITY_PRACTICE_XP.get() / 2), v, w.getGameTime());
        a.addMemory("Patrolled the village (" + s.visited + " stops), all quiet");
        LOG.debug("{} finished a patrol round ({} stops)", a.getName(), s.visited);
        g.setCompleted(true);
        return SkillResult.SUCCESS;
    }

    /** Board, village edges and a few homes, all at walkable surface height and loaded. */
    private static List<BlockPos> buildRoute(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        List<BlockPos> stops = new ArrayList<>();
        List<BlockPos> indoor = new ArrayList<>();
        VillageMarkets.Market market = VillageMarkets.market(w, v.getUUID());
        BlockPos home = v.blockPosition();
        if (market != null) {
            VillageRegistry registry = VillageRegistry.get(w);
            BlockPos board = registry.noticeBoard(market.id);
            stops.add(board != null ? board : market.center);
            MutableBoundingBox b = registry.bounds(market.id);
            if (b != null) {
                int inset = 4;
                int cx = (b.x0 + b.x1) / 2, cz = (b.z0 + b.z1) / 2;
                stops.add(new BlockPos(b.x0 + inset, 0, cz));
                stops.add(new BlockPos(b.x1 - inset, 0, cz));
                stops.add(new BlockPos(cx, 0, b.z0 + inset));
                stops.add(new BlockPos(cx, 0, b.z1 - inset));
                // Check on two homes: residents sleep there and monsters gather at doors at night.
                List<BlockPos> beds = w.getPoiManager().findAll(PointOfInterestType.HOME.getPredicate(),
                        p -> b.isInside(p), market.center, 96, PointOfInterestManager.Status.ANY)
                        .collect(Collectors.toList());
                Collections.shuffle(beds, new Random(v.getRandom().nextLong()));
                for (int i = 0; i < Math.min(2, beds.size()); i++) {
                    // A bed is indoors: stand next to it, not on the roof above it.
                    BlockPos beside = BlockInteractionAction.findStandable(w, beds.get(i));
                    if (beside != null) indoor.add(beside.immutable());
                }
            }
            home = market.center;
        } else {
            for (int i = 0; i < 4; i++)
                stops.add(home.offset(v.getRandom().nextInt(33) - 16, 0, v.getRandom().nextInt(33) - 16));
        }
        List<BlockPos> route = new ArrayList<>();
        for (BlockPos p : stops) {
            if (p == null || !w.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
            BlockPos target = w.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(p.getX(), 0, p.getZ()));
            if (!w.getFluidState(target.below()).isEmpty()) continue;
            if (target.distSqr(home) > 96 * 96) continue;
            route.add(target.immutable());
            if (route.size() >= MAX_STOPS) break;
        }
        for (BlockPos p : indoor) if (route.size() < MAX_STOPS + 2) route.add(p);
        // Keep the board first (a briefing), shuffle the rest.
        if (route.size() > 2) Collections.shuffle(route.subList(1, route.size()), new Random(v.getRandom().nextLong()));
        return route;
    }

    private static void lookAround(VillagerEntity v, long now) {
        double angle = (now / 20 % 8) * Math.PI / 4;
        v.getLookControl().setLookAt(v.getX() + Math.cos(angle) * 8, v.getEyeY(), v.getZ() + Math.sin(angle) * 8);
    }

    private static ItemStack findSword(VillagerAgentData a) {
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack s : a.getInventory().getItems()) {
            if (s.isEmpty() || !(s.getItem() instanceof SwordItem)) continue;
            if (best.isEmpty() || ((SwordItem) s.getItem()).getDamage() > ((SwordItem) best.getItem()).getDamage()) best = s;
        }
        return best;
    }
}
