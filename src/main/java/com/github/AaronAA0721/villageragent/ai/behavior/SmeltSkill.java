package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.*;
import com.github.AaronAA0721.villageragent.ai.proficiency.CapabilityId;
import net.minecraft.block.Blocks;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

/** Physical ore processing. Targeted acquisition waits for real output; routines only load. */
public final class SmeltSkill implements VillagerSkill {
    public static final int TETHER_RADIUS = 16;
    private static final class State {
        BlockPos furnace;
        Item ore;
        ItemStack offered = ItemStack.EMPTY;
        boolean loaded;
        boolean fuelChecked;
        long since;
        /** Remembered furnace being walked to when none is usable nearby; null when not seeking. */
        BlockPos seeking;
        final java.util.Set<BlockPos> rejectedMemory = new java.util.HashSet<>();
        final BlockSeek.Tracker seek = new BlockSeek.Tracker();
    }

    /** Furnaces and blast furnaces, remembered in chunk memory as FURNACE / BLAST_FURNACE. */
    private static final BlockSeek.Kind FURNACES = new BlockSeek.Kind("furnace", 4,
            st -> st.getBlock() == Blocks.FURNACE || st.getBlock() == Blocks.BLAST_FURNACE,
            com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.FURNACE,
            com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.BLAST_FURNACE);
    @Override public String id() { return "smelt"; }
    @Override public boolean isComposite() { return true; }
    @Override public CapabilityId requiredCapability() { return CapabilityId.SMITHING; }
    @Override public int minLevel() { return 1; }
    @Override public boolean canHandle(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        return "smelt".equals(g.getGoalType()) && passesCapabilityGate(a);
    }
    public static Item oreFor(Item output) {
        return output == Items.IRON_INGOT ? Items.IRON_ORE : output == Items.GOLD_INGOT ? Items.GOLD_ORE : Items.AIR;
    }
    @Override public double chanceFactor(VillagerAgentData a, AgentGoal g, long now) {
        return has(a, Items.IRON_ORE) || has(a, Items.GOLD_ORE) ? 1 : 0;
    }
    private static boolean has(VillagerAgentData a, Item item) {
        return a.getInventory().countItem(new ItemStack(item)) > 0;
    }
    public static ItemStack result(ServerWorld w, BlockPos pos, Item ore) {
        Inventory input = new Inventory(new ItemStack(ore));
        if (w.getBlockState(pos).getBlock() == Blocks.BLAST_FURNACE)
            return w.getRecipeManager().getRecipeFor(IRecipeType.BLASTING, input, w)
                    .map(r -> r.getResultItem().copy()).orElse(ItemStack.EMPTY);
        if (w.getBlockState(pos).getBlock() == Blocks.FURNACE)
            return w.getRecipeManager().getRecipeFor(IRecipeType.SMELTING, input, w)
                    .map(r -> r.getResultItem().copy()).orElse(ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }
    @Override public SkillResult execute(VillagerAgentData a, AgentGoal g, VillagerEntity v, ServerWorld w) {
        State s = a.brain().tasks.current().state(State.class, State::new);
        boolean targeted = g.getTargetItem() != null && !g.getTargetItem().isEmpty();
        if (s.ore == null) {
            s.ore = targeted ? oreFor(com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog.item(g.getTargetItem()))
                    : Items.AIR;
            if (targeted && s.ore == Items.AIR) return SkillResult.BLOCKED;
        }
        BlockPos seekFound = null;
        if (s.furnace == null && s.seeking != null) {
            // Walking to a remembered furnace. Each newly entered chunk that holds a furnace triggers a real
            // search from here, so a usable furnace met on the way is taken instead.
            boolean newChunkHasFurnace = BlockSeek.closerOnTheWay(a, v, w, FURNACES, (ww, p) -> true, null, s.seek) != null;
            BlockPos found = newChunkHasFurnace ? findFurnace(a, v, w, s.ore) : null;
            if (found != null) {
                BlockSeek.abortWalk(a, v, w);
                s.seeking = null;
            } else {
                final BlockPos far = s.seeking;
                SkillResult walked = BehaviorExecutor.executeChild(a, v, w, () -> {
                    AgentGoal move = new AgentGoal("move", "Walk to a remembered furnace", g.getPriority());
                    move.setTargetLocation(far);
                    move.setArrivalDistanceSq(9);
                    return move;
                });
                if (walked == SkillResult.RUNNING) return SkillResult.RUNNING;
                s.rejectedMemory.add(s.seeking); // arrived (or could not): search again from here
                s.seeking = null;
                found = findFurnace(a, v, w, s.ore);
            }
            if (found == null) return SkillResult.RUNNING; // next tick: next remembered furnace, build, or block
            seekFound = found;
        }
        if (s.furnace == null) {
            s.furnace = seekFound != null ? seekFound : findFurnace(a, v, w, s.ore);
            if (s.furnace == null && s.rejectedMemory.size() < 3) {
                // None usable in reach: walk to the nearest furnace chunk memory knows of beyond it.
                BlockPos origin = v.blockPosition();
                for (com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature f : FURNACES.features)
                    for (BlockPos p : a.findFeature(f)) {
                        if (s.rejectedMemory.contains(p) || p.distSqr(origin) > (double) BlockSeek.MEMORY_RANGE * BlockSeek.MEMORY_RANGE) continue;
                        if (w.isLoaded(p) && p.distSqr(origin) <= 40 * 40) continue; // already covered by findFurnace
                        if (s.seeking == null || p.distSqr(origin) < s.seeking.distSqr(origin)) s.seeking = p;
                    }
                if (s.seeking != null) {
                    s.seek.reset();
                    BrainLog.diagnostic(a, "seek-choose", "kind=furnace target=" + s.seeking + " (remembered)");
                    return SkillResult.RUNNING;
                }
            }
            if (s.furnace == null && mayBuildFurnace(a, v, w)) {
                // No furnace or blast furnace known anywhere: make one and set it down beside us.
                boolean carried = a.getInventory().countItem(new ItemStack(Items.FURNACE)) > 0;
                SkillResult built = BehaviorExecutor.executeChild(a, v, w, () -> {
                    AgentGoal child = new AgentGoal(carried ? "place_near_self" : "acquire_item",
                            carried ? "Place a furnace" : "Make a furnace", g.getPriority());
                    child.setTargetItem("minecraft:furnace");
                    child.setTargetQuantity(1);
                    return child;
                });
                if (built == SkillResult.SUCCESS || built == SkillResult.RUNNING) return SkillResult.RUNNING;
                if (built == SkillResult.WAITING) return SkillResult.WAITING;
                g.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:furnace"));
                return SkillResult.BLOCKED;
            }
            if (s.furnace == null) {
                g.setLastFailure(FailureReason.NO_JOB_SITE.with("compatible furnace/blast_furnace"));
                return SkillResult.BLOCKED;
            }
            if (!targeted) {
                s.offered = FurnaceInventory.selectOre(a.getInventory(), ((AbstractFurnaceTileEntity) w.getBlockEntity(s.furnace)).getItem(0));
                s.ore = s.offered.getItem();
            } else s.offered = FurnaceInventory.batch(a.getInventory(), new ItemStack(s.ore),
                    ((AbstractFurnaceTileEntity) w.getBlockEntity(s.furnace)).getItem(0));
            s.since = w.getGameTime();
        }
        if (w.getGameTime() - s.since > 1200 || !w.isLoaded(s.furnace)) return SkillResult.FAIL;
        if (v.blockPosition().distSqr(s.furnace) > 4) {
            BlockPos stand = ReachablePaths.standingBeside(w, v, s.furnace);
            if (stand == null) return SkillResult.FAIL;
            SkillResult moveResult = BehaviorExecutor.executeChild(a, v, w, () -> {
                AgentGoal move = new AgentGoal("move", "Walk to furnace", g.getPriority());
                move.setTargetLocation(stand); move.setArrivalDistanceSq(0.5);
                return move;
            });
            if (moveResult != SkillResult.SUCCESS) return moveResult;
        }
        TileEntity te = w.getBlockEntity(s.furnace);
        if (!(te instanceof AbstractFurnaceTileEntity)) return SkillResult.FAIL;
        AbstractFurnaceTileEntity furnace = (AbstractFurnaceTileEntity) te;
        ItemStack product = result(w, s.furnace, s.ore);
        if (product.isEmpty() || targeted && !product.getItem().getRegistryName().toString().equals(g.getTargetItem()))
            return SkillResult.FAIL;
        ItemStack output = furnace.getItem(2);
        if (targeted && !output.isEmpty() && ItemStack.isSame(output, product) && ItemStack.tagMatches(output, product)) {
            int moved = FurnaceInventory.collect(a.getInventory(), furnace);
            if (moved == 0) return SkillResult.FAIL;
            a.gainCapabilityXp(CapabilityId.SMITHING,
                    com.github.AaronAA0721.villageragent.config.ModConfig.CAPABILITY_PRACTICE_XP.get(), v, w.getGameTime());
            if (s.furnace.equals(a.brain().tetherPos) && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty())
                a.brain().clearTether();
            v.swing(Hand.MAIN_HAND); g.markProgress();
            return SkillResult.SUCCESS;
        }
        ItemStack input = furnace.getItem(0);
        if (targeted && !input.isEmpty() && ItemStack.isSame(input, new ItemStack(s.ore))
                && ItemStack.tagMatches(input, new ItemStack(s.ore))) s.loaded = true;
        if (s.loaded) {
            boolean needsFuel = !s.fuelChecked || (!FurnaceInventory.burning(furnace) && furnace.getItem(1).isEmpty());
            s.fuelChecked = true;
            if (needsFuel && !FurnaceInventory.ensureFuel(a.getInventory(), furnace)) {
                g.setLastFailure(FailureReason.MISSING_ITEM.with("minecraft:coal"));
                return SkillResult.BLOCKED;
            }
            return SkillResult.RUNNING;
        }
        // The whole batch goes in at once, so the furnace visibly holds ore, fuel and a growing output stack.
        ItemStack batchProduct = product.copy();
        batchProduct.setCount(Math.min(batchProduct.getMaxStackSize(), product.getCount() * s.offered.getCount()));
        if (!FurnaceInventory.load(a.getInventory(), furnace, s.offered, batchProduct)) return SkillResult.FAIL;
        s.loaded = true;
        s.fuelChecked = true;
        a.brain().tetherPos = s.furnace.immutable();
        int perItem = w.getBlockState(s.furnace).getBlock() == Blocks.BLAST_FURNACE ? 100 : 200;
        a.brain().tetherReadyTick = w.getGameTime() + (long) perItem * s.offered.getCount();
        a.brain().tetherRadius = TETHER_RADIUS;
        v.swing(Hand.MAIN_HAND); g.markProgress();
        BrainLog.diagnostic(a, "smelt-loaded", "furnace=" + s.furnace + " ore=" + s.ore.getRegistryName()
                + " count=" + s.offered.getCount() + " fuel=" + furnace.getItem(1).getCount());
        return targeted ? SkillResult.RUNNING : SkillResult.SUCCESS;
    }
    /**
     * A smelter with no furnace or blast furnace in memory (chunk memory within 128 blocks) or in sight
     * (24 blocks) builds its own. A known furnace that is merely busy is waited for instead.
     */
    public static boolean mayBuildFurnace(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        if (v == null || a.getCapabilityPool().levelOf(CapabilityId.SMITHING) < 1) return false;
        return !knowsFurnace(a, v, w);
    }

    public static boolean knowsFurnace(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        BlockPos origin = v.blockPosition();
        for (BlockPos p : a.findFeature(com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature.FURNACE)) {
            if (p.distSqr(origin) > 128 * 128) continue;
            if (!w.isLoaded(p) || isFurnace(w, p)) return true;
        }
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-24, -6, -24), origin.offset(24, 6, 24)))
            if (w.isLoaded(p) && isFurnace(w, p)) return true;
        return false;
    }

    private static boolean isFurnace(ServerWorld w, BlockPos p) {
        net.minecraft.block.Block block = w.getBlockState(p).getBlock();
        return block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE;
    }

    /** Only loaded, compatible furnaces. Rechecked after movement before any mutation. */
    public static BlockPos findFurnace(VillagerAgentData a, VillagerEntity v, ServerWorld w, Item ore) {
        BlockPos origin = v.blockPosition(), best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-48, -4, -48), origin.offset(48, 4, 48))) {
            if (!w.isLoaded(p)) continue;
            TileEntity te = w.getBlockEntity(p);
            if (!(te instanceof AbstractFurnaceTileEntity)) continue;
            AbstractFurnaceTileEntity candidate = (AbstractFurnaceTileEntity) te;
            ItemStack offered = ore == Items.AIR ? FurnaceInventory.selectOre(a.getInventory(), candidate.getItem(0)) : new ItemStack(ore);
            if (offered.isEmpty()) continue;
            ItemStack product = result(w, p, offered.getItem());
            if (product.isEmpty()) continue;
            product.setCount(Math.min(product.getMaxStackSize(), product.getCount() * offered.getCount()));
            AbstractFurnaceTileEntity furnace = (AbstractFurnaceTileEntity) te;
            ItemStack output = furnace.getItem(2);
            boolean collectible = ore != Items.AIR && !output.isEmpty() && ItemStack.isSame(output, product)
                    && ItemStack.tagMatches(output, product) && InventoryTransfer.spaceFor(a.getInventory(), output) > 0;
            ItemStack fuel = furnace.getItem(1);
            ItemStack input = furnace.getItem(0);
            boolean pending = ore != Items.AIR && !input.isEmpty() && ItemStack.isSame(input, new ItemStack(ore))
                    && ItemStack.tagMatches(input, new ItemStack(ore))
                    && FurnaceInventory.fits(output, product, furnace.getMaxStackSize());
            if (!collectible && ((!pending && !FurnaceInventory.canLoad(furnace, offered, product))
                    || !fuel.isEmpty() && !AbstractFurnaceTileEntity.isFuel(fuel))) continue;
            double distance = approachDistance(v, w, p);
            if (distance < bestDist) { bestDist = distance; best = p.immutable(); }
        }
        return best;
    }
    public static double approachDistance(VillagerEntity v, ServerWorld w, BlockPos furnace) {
        if (v.blockPosition().distSqr(furnace) <= 4) return 0;
        BlockPos stand = ReachablePaths.standingBeside(w, v, furnace);
        return stand == null ? Double.POSITIVE_INFINITY : ReachablePaths.distance(v, stand);
    }
    static BlockPos findFurnaceWithOutput(VillagerAgentData a, VillagerEntity v, ServerWorld w, int radius) {
        BlockPos origin = v.blockPosition(), best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            if (!w.isLoaded(p)) continue;
            TileEntity te = w.getBlockEntity(p);
            if (!(te instanceof AbstractFurnaceTileEntity)) continue;
            ItemStack output = ((AbstractFurnaceTileEntity) te).getItem(2);
            if (output.isEmpty() || InventoryTransfer.spaceFor(a.getInventory(), output) <= 0) continue;
            double distance = approachDistance(v, w, p);
            if (distance < bestDist) { bestDist = distance; best = p.immutable(); }
        }
        return best;
    }
}
