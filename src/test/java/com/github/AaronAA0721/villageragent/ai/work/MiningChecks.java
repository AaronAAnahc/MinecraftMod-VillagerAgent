package com.github.AaronAA0721.villageragent.ai.work;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Production ordering, district state and saved-data tests; no running Minecraft world. */
public final class MiningChecks {
    private static int assertions;
    private static void check(boolean condition, String name) {
        assertions++; if (!condition) throw new AssertionError(name);
    }
    private static final class Survey implements ResourceSensor.MiningScan.View {
        final ResourceCatalog.Source ore = new ResourceCatalog.Source(null, null, null, null, null, false, 1);
        final Map<BlockPos, Double> blocks = new HashMap<>();
        final Set<BlockPos> mined = new HashSet<>(), occupied = new HashSet<>();
        int paths;
        public BlockPos origin() { return new BlockPos(0, 64, 0); }
        public boolean loaded(BlockPos pos) { return true; }
        public ResourceCatalog.Source exposedSource(BlockPos pos) { return blocks.containsKey(pos) ? ore : null; }
        public boolean mined(BlockPos pos) { return mined.contains(pos); }
        public boolean unavailable(BlockPos pos) { return occupied.contains(pos); }
        public boolean standable(BlockPos pos) { return blocks.containsKey(pos.below()); }
        public double distance(BlockPos pos) { paths++; return blocks.getOrDefault(pos.below(), Double.POSITIVE_INFINITY); }
        public double lowerBoundSquared(BlockPos pos) { return 0; }
    }
    private static ResourceSensor.MiningScan survey(Survey view) {
        ResourceSensor.MiningScan scan = new ResourceSensor.MiningScan(view.origin(), Collections.singletonList(view.ore), p -> true);
        check(!scan.tick(view), "one scan budget must not report exhaustion");
        for (int ticks = 0; ticks < 200; ticks++) {
            view.paths = 0; boolean done = scan.tick(view);
            check(view.paths <= 12, "path budget per tick");
            if (done) return scan;
        }
        throw new AssertionError("survey did not terminate");
    }
    public static void main(String[] args) {
        check(MiningPlanner.better(true, 4, 100, 5, 2), "global iron beats nearby coal");
        check(!MiningPlanner.better(true, 5, 2, 4, 100), "global coal cannot displace iron");
        check(MiningPlanner.better(true, 4, 12, 4, 40), "same rarity uses complete path cost");
        check(!MiningPlanner.better(true, 0, Double.POSITIVE_INFINITY, 5, 3), "unreachable diamond unavailable");
        check(MiningPlanner.better(false, 5, 2, 4, 100), "district clearing uses proximity");
        MiningProgress p = new MiningProgress();
        p.refresh(100, 7); p.begin(new BlockPos(-1, 60, -1), "minecraft:iron_ore");
        check(p.inArea(new BlockPos(-32, 1, -32)), "negative-coordinate west/north neighbor included");
        check(p.inArea(new BlockPos(15, 220, 15)), "diagonal neighbor and other height included");
        check(!p.inArea(new BlockPos(16, 60, 0)), "fixed district excludes neighbor-of-neighbor");
        p.lastMine = new BlockPos(14, 65, 14);
        check(!p.inArea(new BlockPos(16, 60, 0)), "moving mine position never expands district");
        p.exhausted(); check(p.phase == MiningProgress.Phase.CLEAR, "primary exhaustion clears ores before leaving");
        p.destination = new BlockPos(3, 50, 4); p.anchorKey = "coal@123"; p.defer("coal@123", 500);
        MiningWorldData data = new MiningWorldData(); data.advance(100);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); BlockPos ore = new BlockPos(3, 50, 4);
        MiningProgress saved = data.progress(a);
        saved.begin(new BlockPos(-1, 60, -1), "minecraft:iron_ore"); saved.phase = MiningProgress.Phase.CLEAR;
        saved.destination = p.destination; saved.anchorKey = p.anchorKey; saved.defer("coal@123", 500);
        check(data.claim(a, ore, 100), "A reserves ore");
        check(!data.claim(b, ore, 101), "B temporarily cannot reserve A's ore");
        check(data.complete(a, ore, 102), "A produces once");
        check(!data.complete(a, ore, 103), "second production entrance cannot produce again for A");
        check(data.claim(b, ore, 103), "B may mine after A, independent daily allowance");
        check(data.complete(b, ore, 104), "B has own yield");
        BlockPos other = ore.above(); data.claim(a, other, 105);
        CompoundNBT tag = data.save(new CompoundNBT());
        MiningWorldData loaded = new MiningWorldData(); loaded.load(tag); loaded.advance(110);
        check(loaded.mined(a, ore) && loaded.mined(b, ore), "personal yields survive save/load");
        check(loaded.claim(b, other, 110), "leases do not survive reload");
        check(loaded.progress(a).phase == MiningProgress.Phase.CLEAR, "clearing phase survives new execution/reload");
        check(loaded.progress(a).destination.equals(p.destination), "resume destination persists");
        check(!loaded.progress(a).ready("coal@123", 499), "deferral persists");
        check(loaded.progress(a).ready("coal@123", 500), "deferral expires at boundary");
        loaded.release(b); check(loaded.claim(a, other, 111), "interruption releases reservation");
        check(loaded.claim(b, other, 211), "unrenewed lease expires");
        loaded.advance(24000);
        check(!loaded.mined(a, ore) && !loaded.mined(b, ore), "next day renews both personal allowances");
        check(loaded.progress(a).active(), "new day retains active district");
        check(new MiningWorldData().progress(a).phase == MiningProgress.Phase.SELECT, "old saves start without district");
        MiningWorldData dimension = new MiningWorldData(); dimension.advance(100);
        check(dimension.complete(a, ore, 100), "dimension data independent");
        p.refresh(24000, 7); check(p.active() && p.ready("coal@123", 24000), "day rollover clears deferrals, preserves clearing");
        p.exhausted(); check(p.phase == MiningProgress.Phase.SELECT, "district complete restarts global rarity");
        p.exhausted(); check(p.phase == MiningProgress.Phase.STONE, "global exhaustion permits stone");
        p.refresh(24001, 8); check(p.phase == MiningProgress.Phase.SELECT, "new tool/skill eligibility revisits ores");
        check(MiningProgress.ironChance(2) == 0, "no iron under level three");
        check(Math.abs(MiningProgress.coalChance(3) - .016) < 1e-10, "level three coal probability");
        check(Math.abs(MiningProgress.ironChance(3) - .001) < 1e-10, "level three iron probability");
        check(MiningProgress.coalChance(100) == .03 && MiningProgress.ironChance(100) == .01, "bonus caps");
        int coal = 0, iron = 0;
        for (int i = 0; i < 100000; i++) {
            int bonus = MiningProgress.bonus(3, (i + .5) / 100000.0);
            if (bonus == 1) coal++; else if (bonus == 2) iron++;
        }
        check(coal == 1600 && iron == 100, "single disjoint draw preserves exact marginal probabilities");
        Survey view = new Survey();
        for (int x = -9; x <= 9; x += 3) for (int z = -9; z <= 9; z += 3)
            view.blocks.put(new BlockPos(x, 64, z), Double.POSITIVE_INFINITY);
        BlockPos far = new BlockPos(9, 64, 9); view.blocks.put(far, 20.0);
        ResourceSensor.MiningScan scan = survey(view);
        check(scan.best != null && scan.best.block.equals(far), "reachable candidate beyond first eight is retained");
        BlockPos near = new BlockPos(0, 64, 3); view.blocks.put(near, 2.0);
        scan = survey(view); check(scan.best.block.equals(near), "nearest path wins regardless of coordinate enumeration");
        view.mined.add(near);
        scan = survey(view); check(scan.best.block.equals(far), "already mined candidate cannot hide another ore");
        view.occupied.add(far);
        scan = survey(view); check(scan.best == null && scan.deferred, "occupied ore is deferred rather than exhausted");
        view.occupied.clear();
        scan = survey(view); check(scan.best.block.equals(far), "released ore remains discoverable for this miner");
        System.out.println("MiningChecks: " + assertions + " assertions passed");
    }
}
