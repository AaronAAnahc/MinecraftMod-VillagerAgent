package com.github.AaronAA0721.villageragent.ai.vision;

import com.github.AaronAA0721.villageragent.ai.memory.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Samples a chunk's contents when a villager first enters it.
 *
 * <p>Strategy (design doc §3.2): scan a thin vertical slab — the whole 16x16 horizontal
 * plane but only Y in [entryY-3, entryY+4] (8 layers around standing height). The scan
 * itself touches 2048 blocks, but <b>nothing per-block is retained</b>: results collapse
 * immediately into
 * <ul>
 *   <li>coarse {@link BlockCategory} counters,</li>
 *   <li>a {@link ChunkFeature} bitmask + one representative position per feature,</li>
 *   <li>{@link ChunkTag} labels derived from the counters,</li>
 *   <li>entity counts per category (positions are not kept — they go stale instantly).</li>
 * </ul>
 * A sampled chunk therefore costs ~100–200 bytes in memory, no matter whether it is empty
 * plains or a 256-block farm.
 *
 * <p>Ore features additionally require <b>reachable</b> exposure: an ore only counts as sighted
 * when it touches the air connected to the viewer's own cell. A mere "has an air face" test
 * would also report ore sealed behind a wall or inside a disconnected cave pocket, which the
 * villager can neither see nor walk to.
 *
 * <p>Cost is bounded and dominated by the block reads that were always there. The read pass
 * does {@code layers * 256} (2048 by default) {@code getBlockState} calls and fills a
 * {@code boolean[]} air bitmap. The connectivity flood fill is skipped entirely unless the
 * slab holds ore the memory does not already know, and when it does run it stays inside that
 * same bitmap — at most 2048 cells, 6 constant-offset neighbour tests each, zero world access.
 * Nothing per-block is retained after the call.
 */
public final class ChunkContentSampler {

    /** Vertical slab half-extents around the reference Y: [refY - BELOW, refY + ABOVE]. */
    private static final int SLAB_BELOW = 3, SLAB_ABOVE = 4;

    /**
     * Ore cells held back for the visibility pass. A slab with more exposed ore than this
     * is a vein wall; checking the first {@value} is more than enough to find a visible face,
     * and the overflow can only cause a conservative "not seen".
     */
    private static final int MAX_ORE_CANDIDATES = 64;

    private ChunkContentSampler() {}

    public static ChunkMemory sample(LivingEntity villager, World world, int cx, int cz) {
        ChunkMemory cm = new ChunkMemory(net.minecraft.util.math.ChunkPos.asLong(cx, cz));
        cm.lastVisitedTick = world.getGameTime();

        BlockPos vp = villager.blockPosition();
        scanSlab(world, cm, cx, cz, vp.getY(), vp);

        deriveTags(cm);
        sampleEntities(villager, world, cm);
        computeSaliency(cm);
        return cm;
    }

    /**
     * Sample a chunk at a fixed reference Y, without a living entity.
     * Used for seeding village shared memories at discovery time.
     * Skips entity sampling (no reference entity available).
     */
    public static ChunkMemory sampleAt(World world, int cx, int cz, int refY) {
        if (!world.hasChunk(cx, cz)) return null;
        ChunkMemory cm = new ChunkMemory(net.minecraft.util.math.ChunkPos.asLong(cx, cz));
        cm.lastVisitedTick = world.getGameTime();

        scanSlab(world, cm, cx, cz, refY, null);

        deriveTags(cm);
        computeSaliency(cm);
        return cm;
    }

    /**
     * Re-read the slab of an already known chunk for its non-ore features only: features that appeared since
     * the last visit are added, and a remembered anchor whose block is gone is replaced by another block of that
     * kind in the slab (or forgotten if there is none). Tags, counts, ore, entities and building ids are kept.
     * Called when a villager re-enters a chunk it has not visited for a while.
     */
    public static void refreshFeatures(World world, ChunkMemory cm, int refY) {
        int cx = cm.getX(), cz = cm.getZ();
        if (!world.hasChunk(cx, cz)) return;
        // Drop anchors that no longer hold their block.
        for (ChunkFeature f : ChunkFeature.values()) {
            if (f.isOre() || !cm.has(f)) continue;
            BlockPos anchor = cm.anchorOf(f);
            if (anchor != null && featureOf(world.getBlockState(anchor)) != f) cm.forgetFeature(f);
        }
        int yLo = Math.max(0, refY - SLAB_BELOW), yHi = Math.min(255, refY + SLAB_ABOVE);
        int x0 = cx << 4, z0 = cz << 4;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = yLo; y <= yHi; y++)
            for (int lx = 0; lx < 16; lx++)
                for (int lz = 0; lz < 16; lz++) {
                    BlockState st = world.getBlockState(p.set(x0 + lx, y, z0 + lz));
                    if (st.getMaterial() == Material.AIR) continue;
                    ChunkFeature f = featureOf(st);
                    if (f != null && !f.isOre() && !cm.has(f)) cm.recordFeature(f, lx, y, lz);
                }
    }

    // ── Slab scan ─────────────────────────────────────────────────────────
    //
    // Cell indexing: i = (ly << 8) | (lx << 4) | lz, so the slab is a flat array of
    // layers * 256 cells and axis steps are plain constants (±1 z, ±16 x, ±256 y).
    // Decomposition is shifts and masks only — no integer division in the inner loop.

    private static final int X_STEP = 16, Y_STEP = 256;

    /**
     * Read the slab once, folding every solid cell into counters and feature bits. Ore cells
     * are deferred: they only become features if they touch the air the {@code viewer} is
     * standing in, which needs a flood fill — and that fill runs <b>only</b> when the slab
     * actually holds unrecorded ore. {@code viewer} may be null (no entity), in which case
     * the open air across the top of the slab seeds the fill.
     */
    private static void scanSlab(World world, ChunkMemory cm, int cx, int cz, int refY, BlockPos viewer) {
        int yLo = Math.max(0, refY - SLAB_BELOW);
        int yHi = Math.min(255, refY + SLAB_ABOVE);
        int layers = yHi - yLo + 1;
        if (layers <= 0) return;
        int x0 = cx << 4, z0 = cz << 4;

        boolean[] air = new boolean[layers << 8];
        int[] oreCells = null;              // allocated on first ore sighting
        ChunkFeature[] oreFeatures = null;
        int oreCount = 0;

        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int ly = 0; ly < layers; ly++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    p.set(x0 + lx, yLo + ly, z0 + lz);
                    BlockState st = world.getBlockState(p);
                    int i = (ly << 8) | (lx << 4) | lz;
                    if (st.getMaterial() == Material.AIR) { air[i] = true; continue; }

                    BlockCategory cat = classify(st);
                    if (cat != null) cm.addCount(cat, 1);

                    // Feature bits: only the first sighting stores a position, the rest are free.
                    ChunkFeature f = featureOf(st);
                    if (f == null || cm.has(f)) continue;
                    if (!f.isOre()) { cm.recordFeature(f, lx, yLo + ly, lz); continue; }
                    // Ore is decided after the fill — see visibility pass below.
                    if (oreCells == null) {
                        oreCells = new int[MAX_ORE_CANDIDATES];
                        oreFeatures = new ChunkFeature[MAX_ORE_CANDIDATES];
                    }
                    if (oreCount < MAX_ORE_CANDIDATES) {
                        oreCells[oreCount] = i;
                        oreFeatures[oreCount] = f;
                        oreCount++;
                    }
                }
            }
        }

        // Common case — plains, forest, any slab without fresh ore: no fill, no extra arrays.
        if (oreCount == 0) return;

        boolean[] reachable = reachableAir(air, layers, viewer, x0, z0, yLo, yHi);
        for (int c = 0; c < oreCount; c++) {
            ChunkFeature f = oreFeatures[c];
            if (cm.has(f)) continue;                                  // an earlier cell won it
            int i = oreCells[c];
            if (!touchesReachableAir(reachable, i, layers)) continue;  // buried or walled off
            cm.recordFeature(f, (i >> 4) & 15, yLo + (i >> 8), i & 15);
        }
    }

    /**
     * Breadth-first flood fill over air cells, seeded at the viewer's own cell (and the one
     * above it, i.e. eye level).
     *
     * <p>The fill cannot run away: it is clipped to the sampled slab, so it visits at most
     * {@code layers * 256} cells (2048 by default) and never leaves the chunk or climbs toward
     * the sky. It also never touches the world — it walks the {@code air} bitmap the read pass
     * already produced. The flip side is that air detouring outside the chunk and back is not
     * followed, an accepted approximation of "the same open space the villager is standing in".
     */
    private static boolean[] reachableAir(boolean[] air, int layers, BlockPos viewer,
                                          int x0, int z0, int yLo, int yHi) {
        boolean[] seen = new boolean[air.length];
        int[] queue = new int[air.length]; // each cell is enqueued at most once
        int head = 0, tail = 0;

        if (viewer != null) {
            int lx = viewer.getX() - x0, lz = viewer.getZ() - z0;
            if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                for (int y = viewer.getY(); y <= Math.min(yHi, viewer.getY() + 1); y++) {
                    if (y < yLo) continue;
                    int i = ((y - yLo) << 8) | (lx << 4) | lz;
                    if (air[i] && !seen[i]) { seen[i] = true; queue[tail++] = i; }
                }
            }
        }
        if (tail == 0) {
            // No viewer, or the viewer's cell is solid (embedded / out of slab): fall back to
            // the open air across the top of the slab as the shared outdoor air set.
            int base = (layers - 1) << 8;
            for (int k = 0; k < 256; k++) {
                int i = base | k;
                if (air[i] && !seen[i]) { seen[i] = true; queue[tail++] = i; }
            }
        }

        int top = (layers - 1) << 8;
        while (head < tail) {
            int i = queue[head++];
            int lz = i & 15, lx = (i >> 4) & 15, yBase = i & ~255;
            if (lz > 0       && air[i - 1]      && !seen[i - 1])      { seen[i - 1]      = true; queue[tail++] = i - 1; }
            if (lz < 15      && air[i + 1]      && !seen[i + 1])      { seen[i + 1]      = true; queue[tail++] = i + 1; }
            if (lx > 0       && air[i - X_STEP] && !seen[i - X_STEP]) { seen[i - X_STEP] = true; queue[tail++] = i - X_STEP; }
            if (lx < 15      && air[i + X_STEP] && !seen[i + X_STEP]) { seen[i + X_STEP] = true; queue[tail++] = i + X_STEP; }
            if (yBase > 0    && air[i - Y_STEP] && !seen[i - Y_STEP]) { seen[i - Y_STEP] = true; queue[tail++] = i - Y_STEP; }
            if (yBase < top  && air[i + Y_STEP] && !seen[i + Y_STEP]) { seen[i + Y_STEP] = true; queue[tail++] = i + Y_STEP; }
        }
        return seen;
    }

    /** True if any of cell {@code i}'s 6 faces is in the reachable air set. */
    private static boolean touchesReachableAir(boolean[] reachable, int i, int layers) {
        int lz = i & 15, lx = (i >> 4) & 15, yBase = i & ~255;
        if (lz > 0                        && reachable[i - 1])      return true;
        if (lz < 15                       && reachable[i + 1])      return true;
        if (lx > 0                        && reachable[i - X_STEP]) return true;
        if (lx < 15                       && reachable[i + X_STEP]) return true;
        if (yBase > 0                     && reachable[i - Y_STEP]) return true;
        if (yBase < ((layers - 1) << 8)   && reachable[i + Y_STEP]) return true;
        return false;
    }

    // ── Block classification ──────────────────────────────────────────────

    /** Map a BlockState to a coarse category, or null for air/ignored blocks. */
    static BlockCategory classify(BlockState s) {
        if (s.getMaterial() == Material.AIR) return null;

        if (s.is(BlockTags.LOGS))            return BlockCategory.WOOD_LOG;
        if (s.is(BlockTags.LEAVES))          return BlockCategory.LEAVES;
        if (s.getBlock() == Blocks.DIRT
                || s.getBlock() == Blocks.COARSE_DIRT
                || s.getBlock() == Blocks.PODZOL
                || s.getBlock() == Blocks.GRASS_BLOCK
                || s.getBlock() == Blocks.GRASS_PATH) return BlockCategory.DIRT;
        if (s.getBlock() == Blocks.SAND
                || s.getBlock() == Blocks.SANDSTONE
                || s.getBlock() == Blocks.RED_SAND) return BlockCategory.SAND;
        if (oreFeature(s.getBlock()) != null) return BlockCategory.ORE;
        if (s.getFluidState().is(FluidTags.WATER)) return BlockCategory.WATER;
        if (s.getBlock() == Blocks.LAVA
                || s.getFluidState().is(FluidTags.LAVA)) return BlockCategory.OTHER; // lava tracked as a feature
        if (s.getBlock() == Blocks.COBBLESTONE
                || s.getBlock() == Blocks.STONE
                || s.is(BlockTags.STONE_BRICKS)) return BlockCategory.STONE;
        if (s.getBlock() == Blocks.FARMLAND || s.is(BlockTags.CROPS)) return BlockCategory.CROP;
        if (isBuildingBlock(s))              return BlockCategory.BUILDING;
        return BlockCategory.OTHER;
    }

    /** The point-of-interest kind this block represents, or null if it is not noteworthy. */
    public static ChunkFeature featureOf(BlockState s) {
        Block b = s.getBlock();

        ChunkFeature ore = oreFeature(b);
        if (ore != null) return ore;

        if (b == Blocks.CRAFTING_TABLE) return ChunkFeature.CRAFTING_TABLE;
        if (b == Blocks.FURNACE)        return ChunkFeature.FURNACE;
        if (b == Blocks.BLAST_FURNACE)  return ChunkFeature.BLAST_FURNACE;
        if (b == Blocks.SMOKER)         return ChunkFeature.SMOKER;
        if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) return ChunkFeature.CAMPFIRE;
        if (b == Blocks.GRAVEL)         return ChunkFeature.GRAVEL;
        if (b == Blocks.CHEST)          return ChunkFeature.CHEST;
        if (b == Blocks.BARREL)         return ChunkFeature.BARREL;
        if (s.is(BlockTags.BEDS))       return ChunkFeature.BED;
        if (s.is(BlockTags.WOODEN_DOORS)) return ChunkFeature.DOOR;
        if (s.is(BlockTags.CROPS))      return ChunkFeature.CROP;
        if (b == Blocks.FARMLAND)       return ChunkFeature.FARMLAND;
        if (s.getFluidState().is(FluidTags.LAVA)) return ChunkFeature.LAVA;
        if (s.getFluidState().is(FluidTags.WATER)) return ChunkFeature.WATER;
        return null;
    }

    private static ChunkFeature oreFeature(Block b) {
        if (b == Blocks.COAL_ORE)     return ChunkFeature.ORE_COAL;
        if (b == Blocks.IRON_ORE)     return ChunkFeature.ORE_IRON;
        if (b == Blocks.GOLD_ORE)     return ChunkFeature.ORE_GOLD;
        if (b == Blocks.DIAMOND_ORE)  return ChunkFeature.ORE_DIAMOND;
        if (b == Blocks.EMERALD_ORE)  return ChunkFeature.ORE_EMERALD;
        if (b == Blocks.REDSTONE_ORE) return ChunkFeature.ORE_REDSTONE;
        if (b == Blocks.LAPIS_ORE)    return ChunkFeature.ORE_LAPIS;
        return null; // COPPER_ORE does not exist in 1.16.5
    }

    /** Artificial blocks that, when numerous, suggest a building / village. */
    private static boolean isBuildingBlock(BlockState s) {
        if (s.is(BlockTags.PLANKS)) return true;
        if (s.is(BlockTags.STONE_BRICKS)) return true;
        if (s.is(BlockTags.WALLS)) return true;
        Block b = s.getBlock();
        return b == Blocks.GLASS || b == Blocks.GLASS_PANE;
    }

    // ── Tag derivation ────────────────────────────────────────────────────

    static void deriveTags(ChunkMemory cm) {
        int wood  = cm.count(BlockCategory.WOOD_LOG) + cm.count(BlockCategory.LEAVES);
        int crop  = cm.count(BlockCategory.CROP);
        int water = cm.count(BlockCategory.WATER);
        int build = cm.count(BlockCategory.BUILDING);

        if (wood  > 30) cm.add(ChunkTag.FOREST);
        if (crop  > 20) cm.add(ChunkTag.FARMLAND);
        if (water >  0) cm.add(ChunkTag.WATER);
        if (build > 40) cm.add(ChunkTag.VILLAGE);
        if (cm.hasAnyOre()) cm.add(ChunkTag.ORE);
        if (cm.has(ChunkFeature.LAVA)) cm.add(ChunkTag.DANGER_LAVA);
    }

    // ── Entity memory (counts only, no positions) ─────────────────────────

    static void sampleEntities(LivingEntity v, World w, ChunkMemory cm) {
        cm.clearEntities();
        cm.entitySeenTick = w.getGameTime();
        AxisAlignedBB box = new AxisAlignedBB(v.blockPosition()).inflate(24); // includes neighbouring chunks
        for (Entity e : w.getEntities(v, box)) { // excludes the villager itself
            cm.addEntity(categorize(e));
        }
        if (cm.entityCount(EntityCategory.HOSTILE) > 0) cm.add(ChunkTag.HOSTILES);
        if (cm.entityCount(EntityCategory.ANIMAL) > 0)  cm.add(ChunkTag.ANIMALS);
    }

    public static EntityCategory categorize(Entity e) {
        if (e instanceof ItemEntity)     return EntityCategory.ITEM;
        if (e instanceof PlayerEntity)   return EntityCategory.PLAYER;
        if (e instanceof VillagerEntity) return EntityCategory.VILLAGER;
        EntityClassification ec = e.getType().getCategory();
        if (ec == EntityClassification.MONSTER) return EntityCategory.HOSTILE;
        if (ec == EntityClassification.CREATURE
                || ec == EntityClassification.WATER_CREATURE
                || ec == EntityClassification.WATER_AMBIENT
                || ec == EntityClassification.AMBIENT) return EntityCategory.ANIMAL;
        return EntityCategory.OTHER;
    }

    // ── Saliency ──────────────────────────────────────────────────────────

    private static void computeSaliency(ChunkMemory cm) {
        float s = 0f;
        if (cm.has(ChunkTag.VILLAGE))     s += 0.5f;
        if (cm.has(ChunkTag.FARMLAND))    s += 0.2f;
        if (cm.has(ChunkTag.ORE))         s += 0.3f;
        if (cm.has(ChunkTag.FOREST))      s += 0.1f;
        if (cm.has(ChunkTag.DANGER_LAVA)) s += 0.15f;
        if (cm.getFeatureFlags() != 0)    s += 0.1f;
        cm.saliency = Math.min(1f, s);
    }
}
