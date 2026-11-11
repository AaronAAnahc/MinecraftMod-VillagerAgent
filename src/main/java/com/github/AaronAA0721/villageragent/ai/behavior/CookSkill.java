package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.work.DropCollector;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.crafting.CampfireCookingRecipe;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;
import net.minecraft.tileentity.CampfireTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Butcher routine 2 of 2 — cooking ("butcher_cook"). Raw meat is cooked in a smoker or on a lit campfire.
 *
 * <ul>
 *   <li><b>Smoker</b> (the butcher's own workstation): first takes any finished food out of the output slot, then
 *       loads one kind of raw meat into the input slot and adds coal/charcoal (one per 8 items, like vanilla) when
 *       the smoker has no fuel. A smoker that cannot be fuelled is skipped in favour of a campfire.</li>
 *   <li><b>Campfire</b> (needs no fuel): raw meat goes on the free slots through the vanilla campfire recipe; the
 *       cooked items pop off after the vanilla cooking time.</li>
 * </ul>
 * The butcher then waits beside the station (up to {@link #MAX_WAIT} ticks) and collects what is ready. Food that
 * is still cooking is collected on the next visit: the station is remembered and makes the routine attractive
 * again once the food is expected to be done.
 */
public final class CookSkill implements VillagerSkill {

    static final Set<Item> RAW_MEAT = new HashSet<>(Arrays.asList(
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON));
    private static final int SCAN = 24;
    private static final int MAX_WAIT = 640;
    /** Vanilla smoker: 100 ticks per item. */
    private static final int SMOKER_TICKS_PER_ITEM = 100;

    /** Station with food still cooking, and when it should be ready. Not persisted: lost food stays in the block. */
    private static final Map<UUID, BlockPos> PENDING = new HashMap<>();
    private static final Map<UUID, Long> PENDING_READY = new HashMap<>();

    private static final class State {
        BlockPos station;
        boolean campfire;
        int phase; // 0 choose, 1 walk, 2 load, 3 wait, 4 collect
        long waitUntil;
        DropCollector drops;
        int loaded;
        final BlockSeek.Tracker seek = new BlockSeek.Tracker();
        final Set<BlockPos> rejected = new HashSet<>();
    }

    /** Smokers and campfires; remembered in chunk memory as SMOKER / CAMPFIRE. */
    private static final BlockSeek.Kind STATION = new BlockSeek.Kind("cooking station", 4,
            st -> st.getBlock() == Blocks.SMOKER || isCampfire(st),
            com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.SMOKER,
            com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.CAMPFIRE);

    @Override public String id() { return "butcher_cook"; }
    @Override public boolean isComposite() { return true; }

    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        int raw = rawCount(a);
        Long ready = PENDING_READY.get(a.getVillagerId());
        boolean collect = ready != null && now >= ready;
        if (raw <= 0 && !collect) return 0; // nothing to cook or collect: never selected
        return Math.min(3, 1 + raw / 8.0 + (collect ? 1 : 0));
    }

    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return id().equals(g.getGoalType());
    }

    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        long now = w.getGameTime();
        a.brain().goalStartedTick = now;

        if (s.phase == 0) {
            BlockPos pending = PENDING.get(a.getVillagerId());
            if (pending != null && rawCount(a) == 0 && isStation(w, pending)) {
                s.station = pending; s.campfire = isCampfire(w.getBlockState(pending));
            } else {
                choose(a, v, w, s);
            }
            if (s.station == null) {
                PENDING.remove(a.getVillagerId()); PENDING_READY.remove(a.getVillagerId());
                g.setLastFailure(FailureReason.UNREACHABLE.with("no usable smoker or lit campfire"));
                return rawCount(a) > 0 ? SkillResult.FAIL : SkillResult.WAITING;
            }
            a.setCurrentActivity("cooking");
            s.phase = 1;
        }

        if (s.phase == 1) {
            // Entering a new chunk on the way: a usable smoker/campfire nearer than the chosen one wins.
            BlockPos closer = BlockSeek.closerOnTheWay(a, v, w, STATION, usable(a, s), s.station, s.seek);
            if (closer != null) {
                BlockSeek.abortWalk(a, v, w);
                s.station = closer;
                s.campfire = isCampfire(w.getBlockState(closer));
            }
            if (v.blockPosition().distSqr(s.station) > 6) {
                final BlockPos target = s.station;
                SkillResult r = BehaviorExecutor.executeChild(a, v, w, () -> {
                    AgentGoal move = new AgentGoal("move", "Walk to the cooking station", g.getPriority());
                    move.setTargetLocation(target);
                    move.setArrivalDistanceSq(4);
                    return move;
                });
                if (r == SkillResult.RUNNING) return r;
                if (r != SkillResult.SUCCESS) {
                    // Try another station before giving up on cooking.
                    s.rejected.add(s.station);
                    if (s.rejected.size() < 3) { s.station = null; s.phase = 0; s.seek.reset(); return SkillResult.RUNNING; }
                    g.setLastFailure(FailureReason.UNREACHABLE.with(String.valueOf(s.station)));
                    return SkillResult.FAIL;
                }
            }
            // A remembered station may have been out of view when chosen: check it now that he is here.
            if (!isStation(w, s.station) || (!s.station.equals(PENDING.get(a.getVillagerId())) && !usable(a, s).test(w, s.station))) {
                s.rejected.add(s.station);
                s.station = null; s.phase = 0; s.seek.reset();
                return SkillResult.RUNNING;
            }
            s.campfire = isCampfire(w.getBlockState(s.station));
            s.phase = 2;
        }

        if (s.phase == 2) {
            v.getNavigation().stop();
            lookAt(v, s.station);
            if (!isStation(w, s.station)) { s.phase = 0; s.station = null; return SkillResult.RUNNING; }
            int collected = s.campfire ? 0 : takeSmokerOutput(a, w, s.station);
            s.loaded = s.campfire ? loadCampfire(a, w, s.station) : loadSmoker(a, w, s.station);
            if (s.loaded > 0 || collected > 0) {
                v.swing(Hand.MAIN_HAND);
                w.playSound(null, s.station.getX() + 0.5, s.station.getY() + 0.5, s.station.getZ() + 0.5,
                        s.campfire ? SoundEvents.CAMPFIRE_CRACKLE : SoundEvents.SMOKER_SMOKE, SoundCategory.BLOCKS, 0.8f, 1.0f);
            }
            if (s.loaded > 0) {
                a.addMemory("Put " + s.loaded + " raw meat on the " + (s.campfire ? "campfire" : "smoker"));
                g.markProgress();
                GoalCommitment.workUnitDone(a, now);
            }
            if (collected > 0) a.addMemory("Took " + collected + " cooked food out of the smoker");
            if (s.loaded == 0 && collected == 0 && !stillCooking(w, s.station, s.campfire)) {
                PENDING.remove(a.getVillagerId()); PENDING_READY.remove(a.getVillagerId());
                return SkillResult.WAITING;
            }
            int cookTicks = s.campfire ? 600 : Math.max(SMOKER_TICKS_PER_ITEM, cookingCount(w, s.station) * SMOKER_TICKS_PER_ITEM);
            PENDING.put(a.getVillagerId(), s.station.immutable());
            PENDING_READY.put(a.getVillagerId(), now + cookTicks);
            s.waitUntil = now + Math.min(MAX_WAIT, cookTicks + 20);
            s.phase = 3;
            return SkillResult.RUNNING;
        }

        if (s.phase == 3) {
            // Stay by the fire and tend it.
            if (v.blockPosition().distSqr(s.station) > 16)
                v.getNavigation().moveTo(s.station.getX() + 0.5, s.station.getY(), s.station.getZ() + 0.5,
                        VillagerNavigationSupport.WALK_SPEED);
            else v.getNavigation().stop();
            if (now % 60 < 40) lookAt(v, s.station);
            boolean done = s.campfire ? !stillCooking(w, s.station, true) : smokerIdle(w, s.station);
            if (now < s.waitUntil && !done) return SkillResult.RUNNING;
            s.phase = 4;
            s.drops = new DropCollector(s.station, 3);
        }

        if (s.phase == 4) {
            if (!s.campfire) {
                int got = takeSmokerOutput(a, w, s.station);
                if (got > 0) a.addMemory("Took " + got + " cooked food out of the smoker");
            }
            if (!s.drops.tick(a, v, w)) return SkillResult.RUNNING;
            if (!stillCooking(w, s.station, s.campfire)) {
                PENDING.remove(a.getVillagerId()); PENDING_READY.remove(a.getVillagerId());
            }
            return SkillResult.SUCCESS;
        }
        return SkillResult.WAITING;
    }

    // ── Station choice ──

    /**
     * Nearest usable smoker or lit campfire: what he sees within {@link #SCAN} blocks, then the stations his chunk
     * memory remembers (see {@link BlockSeek}). A remembered one in an unloaded chunk is walked to and checked there.
     */
    private static void choose(VillagerAgentData a, VillagerEntity v, ServerWorld w, State s) {
        s.station = BlockSeek.nearest(a, v, w, STATION, usable(a, s), SCAN);
        s.campfire = s.station != null && w.isLoaded(s.station) && isCampfire(w.getBlockState(s.station));
        s.seek.reset();
    }

    /** A smoker he can fuel (or that has food to take out), or a lit campfire with a free slot; not rejected. */
    private static java.util.function.BiPredicate<ServerWorld, BlockPos> usable(VillagerAgentData a, State s) {
        boolean hasFuel = fuelSlot(a) >= 0;
        return (w, p) -> {
            if (s.rejected.contains(p)) return false;
            BlockState state = w.getBlockState(p);
            TileEntity te = w.getBlockEntity(p);
            if (state.getBlock() == Blocks.SMOKER) {
                if (!(te instanceof AbstractFurnaceTileEntity)) return false;
                AbstractFurnaceTileEntity f = (AbstractFurnaceTileEntity) te;
                return !f.getItem(1).isEmpty() || hasFuel || !f.getItem(2).isEmpty();
            }
            return isCampfire(state) && CampfireBlock.isLitCampfire(state)
                    && te instanceof CampfireTileEntity && freeSlots((CampfireTileEntity) te) > 0;
        };
    }

    private static boolean isCampfire(BlockState state) {
        return state.getBlock() == Blocks.CAMPFIRE || state.getBlock() == Blocks.SOUL_CAMPFIRE;
    }

    private static boolean isStation(ServerWorld w, BlockPos pos) {
        BlockState state = w.getBlockState(pos);
        return state.getBlock() == Blocks.SMOKER || isCampfire(state);
    }

    // ── Smoker ──

    private static int takeSmokerOutput(VillagerAgentData a, ServerWorld w, BlockPos pos) {
        TileEntity te = w.getBlockEntity(pos);
        if (!(te instanceof AbstractFurnaceTileEntity)) return 0;
        AbstractFurnaceTileEntity f = (AbstractFurnaceTileEntity) te;
        ItemStack out = f.getItem(2);
        if (out.isEmpty()) return 0;
        int moved = InventoryTransfer.insert(a.getInventory(), out.copy());
        if (moved <= 0) return 0;
        out.shrink(moved);
        f.setItem(2, out.isEmpty() ? ItemStack.EMPTY : out);
        f.setChanged();
        return moved;
    }

    private static int loadSmoker(VillagerAgentData a, ServerWorld w, BlockPos pos) {
        TileEntity te = w.getBlockEntity(pos);
        if (!(te instanceof AbstractFurnaceTileEntity)) return 0;
        AbstractFurnaceTileEntity f = (AbstractFurnaceTileEntity) te;
        ItemStack input = f.getItem(0);
        List<ItemStack> items = a.getInventory().getItems();
        int loaded = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !RAW_MEAT.contains(stack.getItem())) continue;
            if (!input.isEmpty() && input.getItem() != stack.getItem()) continue;
            int room = input.isEmpty() ? stack.getMaxStackSize() : input.getMaxStackSize() - input.getCount();
            int n = Math.min(room, stack.getCount());
            if (n <= 0) continue;
            if (input.isEmpty()) { input = new ItemStack(stack.getItem(), n); }
            else input.grow(n);
            stack.shrink(n);
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
            loaded += n;
        }
        if (loaded == 0) return 0;
        f.setItem(0, input);
        // Fuel: one coal/charcoal per 8 items (smoker burns fuel twice as fast but cooks twice as fast).
        ItemStack fuel = f.getItem(1);
        int need = (input.getCount() + 7) / 8 - (fuel.isEmpty() ? 0 : fuel.getCount());
        while (need > 0) {
            int slot = fuelSlot(a);
            if (slot < 0) break;
            ItemStack carried = items.get(slot);
            if (!fuel.isEmpty() && fuel.getItem() != carried.getItem()) break;
            int n = Math.min(need, carried.getCount());
            if (fuel.isEmpty()) fuel = new ItemStack(carried.getItem(), n); else fuel.grow(n);
            carried.shrink(n);
            if (carried.isEmpty()) items.set(slot, ItemStack.EMPTY);
            need -= n;
        }
        f.setItem(1, fuel);
        f.setChanged();
        return loaded;
    }

    private static boolean smokerIdle(ServerWorld w, BlockPos pos) {
        TileEntity te = w.getBlockEntity(pos);
        return !(te instanceof AbstractFurnaceTileEntity) || ((AbstractFurnaceTileEntity) te).getItem(0).isEmpty();
    }

    private static int cookingCount(ServerWorld w, BlockPos pos) {
        TileEntity te = w.getBlockEntity(pos);
        return te instanceof AbstractFurnaceTileEntity ? ((AbstractFurnaceTileEntity) te).getItem(0).getCount() : 0;
    }

    private static int fuelSlot(VillagerAgentData a) {
        List<ItemStack> items = a.getInventory().getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty() && (s.getItem() == Items.COAL || s.getItem() == Items.CHARCOAL)) return i;
        }
        return -1;
    }

    // ── Campfire ──

    private static int freeSlots(CampfireTileEntity te) {
        int free = 0;
        for (ItemStack s : te.getItems()) if (s.isEmpty()) free++;
        return free;
    }

    private static int loadCampfire(VillagerAgentData a, ServerWorld w, BlockPos pos) {
        TileEntity te = w.getBlockEntity(pos);
        if (!(te instanceof CampfireTileEntity)) return 0;
        CampfireTileEntity fire = (CampfireTileEntity) te;
        List<ItemStack> items = a.getInventory().getItems();
        int loaded = 0;
        for (int i = 0; i < items.size() && freeSlots(fire) > 0; i++) {
            ItemStack stack = items.get(i);
            while (!stack.isEmpty() && RAW_MEAT.contains(stack.getItem()) && freeSlots(fire) > 0) {
                Optional<CampfireCookingRecipe> recipe = fire.getCookableRecipe(stack);
                if (!recipe.isPresent()) break;
                if (!fire.placeFood(new ItemStack(stack.getItem()), recipe.get().getCookingTime())) break;
                stack.shrink(1);
                loaded++;
            }
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
        }
        return loaded;
    }

    private static boolean stillCooking(ServerWorld w, BlockPos pos, boolean campfire) {
        TileEntity te = w.getBlockEntity(pos);
        if (campfire) return te instanceof CampfireTileEntity && freeSlots((CampfireTileEntity) te) < 4;
        return te instanceof AbstractFurnaceTileEntity && !((AbstractFurnaceTileEntity) te).getItem(0).isEmpty();
    }

    // ── Misc ──

    static int rawCount(VillagerAgentData a) {
        int n = 0;
        for (ItemStack s : a.getInventory().getItems()) if (!s.isEmpty() && RAW_MEAT.contains(s.getItem())) n += s.getCount();
        return n;
    }

    private static void lookAt(VillagerEntity v, BlockPos pos) {
        v.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
