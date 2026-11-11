package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import com.github.AaronAA0721.villageragent.ai.work.DropCollector;
import com.github.AaronAA0721.villageragent.ai.work.MiningWorldData;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShovelItem;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Fletcher routine ("fletcher_work"): keep a stock of arrows and the materials for them.
 *
 * <ul>
 *   <li><b>Fletch</b> — with flint, a stick and a feather, craft 4 arrows (vanilla recipe) at the nearest fletching
 *       table, or where he stands when there is none. Repeats up to {@link #ARROW_STOCK} arrows.</li>
 *   <li><b>Dig gravel</b> — with a shovel, dig an exposed gravel block like the miner mines ore: the block is never
 *       removed, each dig has a {@link #FLINT_CHANCE} chance to yield one flint and otherwise yields nothing (never
 *       gravel). Each gravel block can be dug once per day per fletcher (shared {@link MiningWorldData} ledger).</li>
 *   <li><b>Hunt chickens</b> — for feathers, under the {@link AnimalCare} rules: a village pair is kept, extra adult
 *       chickens and chickens outside the village may be killed. Drops (feathers, raw chicken) are collected.</li>
 * </ul>
 * Sticks are not gathered here: when none are left the fletcher posts an acquisition for them.
 * The bow itself is a profession essential ({@code profession_essentials.json}); a fletcher without one gets the
 * shared "acquire profession tool bow" goal, which crafts it or asks the village for string.
 */
public final class FletcherSkill implements VillagerSkill {

    public static final int ARROW_STOCK = 64;
    /** Flint and feathers kept in stock beyond what the next arrows need. */
    private static final int MATERIAL_STOCK = 8;
    public static final float FLINT_CHANCE = 0.25f;
    private static final int CRAFT_TICKS = 40;
    private static final int UNITS_PER_SESSION = 4;
    private static final int MAX_FAILURES = 3;
    private static final int GRAVEL_SCAN = 24;
    /** Radius of the second, village-wide gravel scan. */
    private static final int VILLAGE_GRAVEL_SCAN = 48;
    /** After a scan found no gravel, do not rescan for this long. */
    private static final long NO_GRAVEL_RETRY = 1200;
    private static final Map<UUID, Long> NO_GRAVEL_UNTIL = new HashMap<>();

    private enum Mode { NONE, CRAFT, DIG, HUNT }

    /** Gravel, remembered in chunk memory as GRAVEL. */
    private static final BlockSeek.Kind GRAVEL = new BlockSeek.Kind("gravel", 6,
            st -> st.getBlock() == Blocks.GRAVEL, com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.GRAVEL);

    private static final class State {
        Mode mode = Mode.NONE;
        BlockPos table;
        boolean walked;
        int craftTicks;
        AnimalJob hunt;
        DropCollector drops;
        BlockPos gravel;
        /** The dig action (kept here: a walk child to remembered gravel replaces the villager's current action). */
        VillagerAction digging;
        int done, failures;
        final Set<BlockPos> rejectedGravel = new HashSet<>();
        final BlockSeek.Tracker seek = new BlockSeek.Tracker();
        final Set<UUID> rejectedAnimals = new HashSet<>();
    }

    @Override public String id() { return "fletcher_work"; }
    @Override public boolean isComposite() { return true; }

    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        int arrows = count(a, Items.ARROW);
        if (arrows >= ARROW_STOCK && count(a, Items.FLINT) >= MATERIAL_STOCK
                && count(a, Items.FEATHER) >= MATERIAL_STOCK) return 0.2;
        return Math.min(3, 1 + (ARROW_STOCK - Math.min(ARROW_STOCK, arrows)) / 32.0);
    }

    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }

    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        a.brain().goalStartedTick = now;
        if (s.mode == Mode.NONE) {
            if (s.done >= UNITS_PER_SESSION) return SkillResult.SUCCESS;
            if (s.failures >= MAX_FAILURES) {
                if (s.done > 0) return SkillResult.SUCCESS;
                g.setLastFailure(FailureReason.UNREACHABLE.with("no reachable gravel or chickens"));
                return SkillResult.FAIL;
            }
            s.mode = decide(a, v, w, s, now);
            if (s.mode == Mode.NONE) {
                // Nothing doable now: stocked up, or an input is missing and cannot be gathered here.
                if (s.done > 0 || count(a, Items.ARROW) >= ARROW_STOCK) return SkillResult.SUCCESS;
                String missing = count(a, Items.STICK) == 0 ? "minecraft:stick"
                        : count(a, Items.FLINT) == 0 ? "minecraft:flint"
                        : count(a, Items.FEATHER) == 0 ? "minecraft:feather" : null;
                if (missing != null) g.setLastFailure(FailureReason.MISSING_ITEM.with(missing));
                return SkillResult.WAITING;
            }
        }
        switch (s.mode) {
            case CRAFT: return craft(a, g, v, w, s, now);
            case DIG:   return dig(a, g, v, w, s, now);
            case HUNT:  return hunt(a, g, v, w, s, now);
            default:    return SkillResult.WAITING;
        }
    }

    // ── Decision ──

    private Mode decide(VillagerAgentData a, VillagerEntity v, ServerWorld w, State s, long now) {
        int arrows = count(a, Items.ARROW), flint = count(a, Items.FLINT), feathers = count(a, Items.FEATHER);
        int sticks = count(a, Items.STICK);
        if (sticks == 0) FailureRecoveryPlanner.requireItem(a, "minecraft:stick", MATERIAL_STOCK);
        if (arrows < ARROW_STOCK && flint > 0 && sticks > 0 && feathers > 0) {
            s.table = findTable(v, w);
            s.walked = s.table == null;
            s.craftTicks = 0;
            a.setCurrentActivity("fletching arrows");
            return Mode.CRAFT;
        }
        if (flint < MATERIAL_STOCK && !shovel(a).isEmpty()) {
            BlockPos gravel = findGravel(a, v, w, s, now);
            if (gravel != null) {
                s.gravel = gravel;
                VillagerAction digging = new VillagerAction(VillagerAction.ActionType.BREAK, "dig_gravel");
                digging.setTargetBlockPos(gravel);
                digging.setBreakTool(shovel(a));
                digging.setGoalDriven(true);
                digging.setPhase(VillagerAction.ActionPhase.WALKING);
                a.setCurrentAction(digging);
                s.digging = digging;
                a.setCurrentActivity("digging gravel for flint");
                return Mode.DIG;
            }
        }
        if (feathers < MATERIAL_STOCK) {
            AnimalCare.Plan plan = AnimalCare.plan(a, v, w, type -> type == EntityType.CHICKEN,
                    true, true, true, s.rejectedAnimals);
            if (plan != null) {
                s.hunt = new AnimalJob(plan);
                a.setCurrentActivity(plan.kind == AnimalCare.Kind.FEED ? "feeding chickens" : "hunting chickens");
                return Mode.HUNT;
            }
            // No chicken he may take: ask the village (board / other villagers / player) for feathers
            // instead of waiting forever.
            if (feathers == 0) FailureRecoveryPlanner.requireItem(a, "minecraft:feather", MATERIAL_STOCK);
        }
        return Mode.NONE;
    }

    // ── Fletching ──

    private SkillResult craft(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s, long now) {
        if (!s.walked) {
            if (w.getBlockState(s.table).getBlock() != Blocks.FLETCHING_TABLE) { s.walked = true; }
            else if (v.blockPosition().distSqr(s.table) > 6) {
                final BlockPos target = s.table;
                SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                    AgentGoal move = new AgentGoal("move", "Walk to the fletching table", g.getPriority());
                    move.setTargetLocation(target);
                    move.setArrivalDistanceSq(4);
                    return move;
                });
                if (r == SkillResult.RUNNING) return r;
                s.walked = true; // unreachable table: fletch where he stands
            } else s.walked = true;
        }
        v.getNavigation().stop();
        if (s.table != null) v.getLookControl().setLookAt(s.table.getX() + 0.5, s.table.getY() + 1.0, s.table.getZ() + 0.5);
        if (++s.craftTicks % 10 == 0) v.swing(Hand.MAIN_HAND);
        if (s.craftTicks < CRAFT_TICKS) return SkillResult.RUNNING;
        s.craftTicks = 0;
        if (count(a, Items.FLINT) < 1 || count(a, Items.STICK) < 1 || count(a, Items.FEATHER) < 1
                || count(a, Items.ARROW) >= ARROW_STOCK
                || InventoryTransfer.spaceFor(a.getInventory(), new ItemStack(Items.ARROW)) < 4) {
            s.mode = Mode.NONE;
            return SkillResult.RUNNING;
        }
        a.getInventory().removeItem(new ItemStack(Items.FLINT), 1);
        a.getInventory().removeItem(new ItemStack(Items.STICK), 1);
        a.getInventory().removeItem(new ItemStack(Items.FEATHER), 1);
        InventoryTransfer.insert(a.getInventory(), new ItemStack(Items.ARROW, 4));
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_WORK_FLETCHER, SoundCategory.NEUTRAL, 0.8f, 1.0f);
        a.addMemory("Fletched 4 arrows");
        a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
        unitDone(a, g, s, now);
        // Keep fletching while materials last; the session ends after UNITS_PER_SESSION batches.
        if (s.done >= UNITS_PER_SESSION) { s.mode = Mode.NONE; }
        return SkillResult.RUNNING;
    }

    private static BlockPos findTable(VillagerEntity v, ServerWorld w) {
        BlockPos origin = v.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-16, -3, -16), origin.offset(16, 3, 16))) {
            if (w.getBlockState(p).getBlock() != Blocks.FLETCHING_TABLE) continue;
            double d = p.distSqr(origin);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    // ── Gravel ──

    private SkillResult dig(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s, long now) {
        if (s.drops != null) {
            if (!s.drops.tick(a, v, w)) return SkillResult.RUNNING;
            s.drops = null;
            s.mode = Mode.NONE;
            return SkillResult.RUNNING;
        }
        VillagerAction action = s.digging;
        if (action == null || (w.isLoaded(s.gravel) && w.getBlockState(s.gravel).getBlock() != Blocks.GRAVEL)) {
            ActionLifecycle.clearBody(a, v, w);
            s.mode = Mode.NONE;
            return SkillResult.RUNNING;
        }
        // Still walking: entering a new chunk, closer diggable gravel replaces the chosen block.
        if (action.getPhase() != VillagerAction.ActionPhase.ACTING) {
            BlockPos closer = BlockSeek.closerOnTheWay(a, v, w, GRAVEL, diggable(v, s, now), s.gravel, s.seek);
            if (closer != null) {
                BlockSeek.abortWalk(a, v, w); // walking to remembered gravel: drop that walk
                w.destroyBlockProgress(v.getId(), s.gravel, -1);
                MiningWorldData.get(w).release(v.getUUID());
                s.gravel = closer;
                action.setTargetBlockPos(closer);
                action.setStandCell(null);
                action.setPhase(VillagerAction.ActionPhase.WALKING);
                v.getNavigation().stop();
            }
        }
        // Remembered gravel in a chunk that is not loaded: walk toward it first, then dig as usual.
        if (!w.isLoaded(s.gravel)) {
            final BlockPos far = s.gravel;
            SkillResult walked = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to remembered gravel", g.getPriority());
                move.setTargetLocation(far);
                move.setArrivalDistanceSq(16 * 16);
                return move;
            });
            if (walked == SkillResult.RUNNING) return SkillResult.RUNNING;
            if (!w.isLoaded(s.gravel)) {
                s.rejectedGravel.add(s.gravel);
                s.failures++;
                ActionLifecycle.clearBody(a, v, w);
                s.mode = Mode.NONE;
                return SkillResult.RUNNING;
            }
        }
        if (a.getCurrentAction() != action) a.setCurrentAction(action);
        BlockInteractionAction.StepResult r = BlockInteractionAction.tickMine(v, w, a, action, Items.FLINT, 1, FLINT_CHANCE);
        if (r == BlockInteractionAction.StepResult.IN_PROGRESS) return SkillResult.RUNNING;
        BlockPos dug = s.gravel;
        ActionLifecycle.clearBody(a, v, w);
        s.digging = null;
        if (r == BlockInteractionAction.StepResult.ABANDONED) {
            s.rejectedGravel.add(dug);
            s.failures++;
            s.mode = Mode.NONE;
            return SkillResult.RUNNING;
        }
        w.playSound(null, dug.getX() + 0.5, dug.getY() + 0.5, dug.getZ() + 0.5, SoundEvents.GRAVEL_HIT, SoundCategory.BLOCKS, 1.0f, 0.8f);
        a.gainCapabilityXp(CapabilityId.CRAFTING, ModConfig.CAPABILITY_PRACTICE_XP.get(), v, now);
        unitDone(a, g, s, now);
        s.drops = new DropCollector(dug, 3);
        return SkillResult.RUNNING;
    }

    /**
     * Nearest exposed, dry gravel. First a close scan around the fletcher; if nothing is there, a wider scan
     * around his village (centre and job site), since the fletcher idles at his table and the village's gravel
     * (paths, river banks) is usually further than {@link #GRAVEL_SCAN} blocks from it.
     */
    private static BlockPos findGravel(VillagerAgentData a, VillagerEntity v, ServerWorld w, State s, long now) {
        Long retry = NO_GRAVEL_UNTIL.get(v.getUUID());
        if (retry != null && now < retry) return null;
        BlockPos origin = v.blockPosition();
        // What he sees within GRAVEL_SCAN, then gravel his chunk memory remembers (BlockSeek).
        BlockPos best = BlockSeek.nearest(a, v, w, GRAVEL, diggable(v, s, now), GRAVEL_SCAN);
        s.seek.reset();
        if (best == null) {
            for (BlockPos centre : villageAnchors(v, w)) {
                BlockPos found = scanGravel(v, w, s, now, centre, VILLAGE_GRAVEL_SCAN, origin);
                if (found != null && (best == null || found.distSqr(origin) < best.distSqr(origin))) best = found;
            }
        }
        BrainLog.diagnostic(a, "fletcher-gravel", "origin=" + origin + " found=" + best);
        if (best == null) NO_GRAVEL_UNTIL.put(v.getUUID(), now + NO_GRAVEL_RETRY);
        return best;
    }

    /** Exposed gravel this fletcher has not dug today, nobody is digging, and he has not given up on. */
    private static java.util.function.BiPredicate<ServerWorld, BlockPos> diggable(VillagerEntity v, State s, long now) {
        return (w, p) -> {
            if (s.rejectedGravel.contains(p)) return false;
            MiningWorldData ledger = MiningWorldData.get(w);
            return !ledger.mined(v.getUUID(), p) && !ledger.occupied(v.getUUID(), p, now) && exposed(w, p);
        };
    }

    private static BlockPos scanGravel(VillagerEntity v, ServerWorld w, State s, long now,
                                       BlockPos centre, int radius, BlockPos rankFrom) {
        MiningWorldData ledger = MiningWorldData.get(w);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-radius, -8, -radius), centre.offset(radius, 6, radius))) {
            if (!w.isLoaded(p) || w.getBlockState(p).getBlock() != Blocks.GRAVEL) continue;
            BlockPos pos = p.immutable();
            if (s.rejectedGravel.contains(pos) || ledger.mined(v.getUUID(), pos) || ledger.occupied(v.getUUID(), pos, now)) continue;
            if (!exposed(w, pos)) continue;
            double d = pos.distSqr(rankFrom);
            if (d < bestD) { bestD = d; best = pos; }
        }
        return best;
    }

    /** Where to look for gravel beyond the fletcher's surroundings: his village centre and his job site. */
    private static List<BlockPos> villageAnchors(VillagerEntity v, ServerWorld w) {
        List<BlockPos> anchors = new ArrayList<>();
        VillageRegistry registry = VillageRegistry.get(w);
        Long key = registry.villageNear(w, v.blockPosition(), 32);
        if (key != null) anchors.add(registry.center(key));
        v.getBrain().getMemory(net.minecraft.entity.ai.brain.memory.MemoryModuleType.JOB_SITE).ifPresent(site -> {
            if (site.dimension() == w.dimension() && site.pos().distSqr(v.blockPosition()) > 16 * 16) anchors.add(site.pos());
        });
        return anchors;
    }

    /** Dry gravel the fletcher can stand next to: open air above or on a side. */
    private static boolean exposed(ServerWorld w, BlockPos pos) {
        if (w.isEmptyBlock(pos.above())) return true;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (w.isEmptyBlock(pos.relative(d)) && w.isEmptyBlock(pos.relative(d).above())) return true;
        return false;
    }

    // ── Chickens ──

    private SkillResult hunt(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w, State s, long now) {
        AnimalJob.Status status = s.hunt.tick(a, v, w);
        if (status == AnimalJob.Status.RUNNING) return SkillResult.RUNNING;
        AnimalJob job = s.hunt;
        s.hunt = null;
        s.mode = Mode.NONE;
        if (status == AnimalJob.Status.FAILED) {
            s.rejectedAnimals.add(job.plan.animal.getUUID());
            s.failures++;
            return SkillResult.RUNNING;
        }
        a.addMemory(job.plan.kind == AnimalCare.Kind.FEED ? "Fed the chickens" : "Hunted a chicken for feathers");
        unitDone(a, g, s, now);
        return SkillResult.RUNNING;
    }

    // ── Helpers ──

    private static void unitDone(VillagerAgentData a, AgentGoal g, State s, long now) {
        s.done++;
        g.markProgress();
        GoalCommitment.workUnitDone(a, now);
    }

    static int count(VillagerAgentData a, Item item) {
        int n = 0;
        for (ItemStack stack : a.getInventory().getItems()) if (!stack.isEmpty() && stack.getItem() == item) n += stack.getCount();
        return n;
    }

    private static ItemStack shovel(VillagerAgentData a) {
        for (ItemStack stack : a.getInventory().getItems())
            if (!stack.isEmpty() && stack.getItem() instanceof ShovelItem) return stack;
        return ItemStack.EMPTY;
    }
}
