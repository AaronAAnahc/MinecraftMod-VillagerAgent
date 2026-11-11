package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.vision.ChunkContentSampler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Memory-first search for "a block of some kind" (a smoker or campfire, gravel, a furnace, open water...).
 *
 * <ol>
 *   <li><b>Choose</b> ({@link #nearest}): the nearest usable block among what the villager can see around him and
 *       the positions his chunk memory remembers for that kind ({@link ChunkFeature} anchors, one per chunk).
 *       A remembered block in a loaded chunk is checked first; if it is gone or unusable the chunk is re-read and
 *       memory corrected. A remembered block in an unloaded chunk is accepted as a walk target and checked later.</li>
 *   <li><b>Re-check on the way</b> ({@link #closerOnTheWay}): every time the villager enters a new chunk while
 *       walking, that chunk is scanned for the same kind. A usable block nearer than the current destination
 *       replaces it; the caller drops its walk ({@link #abortWalk}) and heads there instead.</li>
 * </ol>
 * Every block of the kind seen during a scan is written into chunk memory, so the next search starts better.
 * Server thread only.
 */
public final class BlockSeek {

    /** How far back remembered blocks are considered (blocks). */
    public static final int MEMORY_RANGE = 128;
    /** At most this many remembered anchors are verified per search. */
    private static final int MAX_ANCHORS = 16;

    /** A kind of block: which blocks qualify and which chunk-memory features remember them. */
    public static final class Kind {
        public final String name;
        public final Predicate<BlockState> type;
        public final ChunkFeature[] features;
        /** Vertical half-height of chunk scans, around the villager (on the way) or the anchor (verification). */
        public final int yRange;

        public Kind(String name, int yRange, Predicate<BlockState> type, ChunkFeature... features) {
            this.name = name; this.type = type; this.features = features; this.yRange = yRange;
        }
    }

    /** Per-search state a skill keeps: the last chunk scanned on the way. */
    public static final class Tracker {
        long lastChunk = Long.MIN_VALUE;
        /** Forget the last scanned chunk (e.g. after choosing a new target), so the current chunk is scanned again. */
        public void reset() { lastChunk = Long.MIN_VALUE; }
    }

    private BlockSeek() {}

    // ── Choose ──

    /**
     * Nearest usable block of {@code kind}: a box of {@code localRadius} around the villager, then remembered anchors
     * within {@link #MEMORY_RANGE}. {@code usable} is checked only on loaded positions; it must include whatever
     * makes a block acceptable beyond its type (fuel, free slot, not rejected, exposed...).
     *
     * @return a loaded, usable block, or an unverified remembered position in an unloaded chunk; null if none
     */
    public static BlockPos nearest(VillagerAgentData a, VillagerEntity v, ServerWorld w, Kind kind,
                                   BiPredicate<ServerWorld, BlockPos> usable, int localRadius) {
        BlockPos origin = v.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        if (localRadius > 0) {
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-localRadius, -kind.yRange, -localRadius),
                    origin.offset(localRadius, kind.yRange, localRadius))) {
                if (!w.isLoaded(p)) continue;
                BlockState state = w.getBlockState(p);
                if (!kind.type.test(state)) continue;
                BlockPos pos = p.immutable();
                remember(a, w, pos, state);
                double d = pos.distSqr(origin);
                if (d < bestD && usable.test(w, pos)) { bestD = d; best = pos; }
            }
        }
        List<BlockPos> anchors = new ArrayList<>();
        for (ChunkFeature f : kind.features)
            for (BlockPos p : a.findFeature(f))
                if (p.distSqr(origin) <= (double) MEMORY_RANGE * MEMORY_RANGE) anchors.add(p);
        anchors.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        int checked = 0;
        for (BlockPos anchor : anchors) {
            if (anchor.distSqr(origin) >= bestD || ++checked > MAX_ANCHORS) break;
            if (!w.isLoaded(anchor)) { best = anchor; bestD = anchor.distSqr(origin); break; } // check on arrival
            if (kind.type.test(w.getBlockState(anchor)) && usable.test(w, anchor)) {
                best = anchor; bestD = anchor.distSqr(origin); break;
            }
            // Gone or unusable: re-read that chunk (corrects memory) for another one of the kind.
            BlockPos alt = scanChunk(a, w, kind, usable, anchor.getX() >> 4, anchor.getZ() >> 4, anchor.getY(), origin);
            if (alt != null && alt.distSqr(origin) < bestD) { best = alt; bestD = alt.distSqr(origin); }
        }
        if (best != null) BrainLog.diagnostic(a, "seek-choose", "kind=" + kind.name + " target=" + best
                + " loaded=" + w.isLoaded(best) + " remembered=" + anchors.size());
        return best;
    }

    // ── Re-check on the way ──

    /**
     * Call every tick while walking toward {@code current}. When the villager has just entered a chunk, scan it
     * for {@code kind}; return a usable block nearer to him than {@code current} (by at least 2 blocks), else null.
     * Also returns a replacement when {@code current} is now loaded but no longer usable (and something else is).
     */
    public static BlockPos closerOnTheWay(VillagerAgentData a, VillagerEntity v, ServerWorld w, Kind kind,
                                          BiPredicate<ServerWorld, BlockPos> usable, BlockPos current, Tracker tracker) {
        BlockPos here = v.blockPosition();
        long chunk = ChunkPos.asLong(here.getX() >> 4, here.getZ() >> 4);
        if (chunk == tracker.lastChunk) return null;
        tracker.lastChunk = chunk;
        BlockPos found = scanChunk(a, w, kind, usable, here.getX() >> 4, here.getZ() >> 4, here.getY(), here);
        boolean currentBad = current != null && w.isLoaded(current)
                && !(kind.type.test(w.getBlockState(current)) && usable.test(w, current));
        if (found == null) return null;
        if (current == null || currentBad || found.distSqr(here) + 4 < current.distSqr(here)) {
            BrainLog.diagnostic(a, "seek-retarget", "kind=" + kind.name + " from=" + current + " to=" + found
                    + (currentBad ? " (old target unusable)" : ""));
            return found;
        }
        return null;
    }

    /** Drop the walk child of the running task (the caller is about to walk somewhere else). */
    public static void abortWalk(VillagerAgentData a, VillagerEntity v, ServerWorld w) {
        if (a.brain().tasks.current().child() == null) return;
        ActionLifecycle.clearBody(a, v, w);
        ActionLifecycle.releaseTree(a.brain().tasks.current().child(), a, w);
        a.brain().tasks.discardChild();
    }

    // ── Helpers ──

    /**
     * Scan one chunk (a slab of {@code kind.yRange} around {@code refY}) for {@code kind}; every block of the kind is
     * remembered, and a remembered anchor of the kind in this chunk that no longer holds it is forgotten.
     * Returns the usable one nearest to {@code rankFrom}, or null. Unloaded chunks are skipped.
     */
    public static BlockPos scanChunk(VillagerAgentData a, ServerWorld w, Kind kind, BiPredicate<ServerWorld, BlockPos> usable,
                                     int cx, int cz, int refY, BlockPos rankFrom) {
        if (!w.hasChunk(cx, cz)) return null;
        ChunkMemory cm = a.getChunkMemory(cx, cz);
        if (cm != null) for (ChunkFeature f : kind.features) {
            BlockPos anchor = cm.anchorOf(f);
            if (anchor != null && ChunkContentSampler.featureOf(w.getBlockState(anchor)) != f) cm.forgetFeature(f);
        }
        int yLo = Math.max(0, refY - kind.yRange), yHi = Math.min(255, refY + kind.yRange);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = yLo; y <= yHi; y++)
            for (int lx = 0; lx < 16; lx++)
                for (int lz = 0; lz < 16; lz++) {
                    p.set((cx << 4) + lx, y, (cz << 4) + lz);
                    BlockState state = w.getBlockState(p);
                    if (!kind.type.test(state)) continue;
                    BlockPos pos = p.immutable();
                    remember(a, w, pos, state);
                    double d = pos.distSqr(rankFrom);
                    if (d < bestD && usable.test(w, pos)) { bestD = d; best = pos; }
                }
        return best;
    }

    /** Record {@code pos} as this chunk's anchor for its feature, unless a valid anchor is already remembered. */
    private static void remember(VillagerAgentData a, ServerWorld w, BlockPos pos, BlockState state) {
        ChunkFeature f = ChunkContentSampler.featureOf(state);
        if (f == null || f.isOre()) return;
        ChunkMemory cm = a.getChunkMemory(pos.getX() >> 4, pos.getZ() >> 4);
        if (cm == null) return; // never visited or seen: the chunk sampler will pick it up on entry
        if (cm.has(f)) {
            BlockPos anchor = cm.anchorOf(f);
            if (anchor == null || !w.isLoaded(anchor) || ChunkContentSampler.featureOf(w.getBlockState(anchor)) == f) return;
            cm.forgetFeature(f);
        }
        cm.recordFeature(f, pos.getX() & 15, pos.getY(), pos.getZ() & 15);
    }
}
