package com.github.AaronAA0721.villageragent.ai.memory;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A villager's memory of a single chunk — <b>labels only, never a block cache</b>.
 *
 * <p>The chunk sampler reads ~2048 blocks when the villager first walks in, but nothing
 * about those reads is kept: the scan collapses into
 * <ul>
 *   <li>{@link ChunkTag} bitmask (forest / farmland / village / ore / lava ...) — 4 bytes,</li>
 *   <li>{@link ChunkFeature} bitmask + <b>one</b> packed representative position per feature
 *       ("there is a crafting table here, roughly at ..."),</li>
 *   <li>coarse per-{@link BlockCategory} counts (used to derive the tags),</li>
 *   <li>entity counts per {@link EntityCategory} (no positions — they go stale in seconds).</li>
 * </ul>
 *
 * <p>Total footprint is ~100–200 bytes per chunk (and ~40 bytes for a chunk that was only
 * ever seen from a neighbour), versus the tens of kilobytes an unbounded per-block list cost
 * on farm / cave chunks. All arrays are allocated lazily.
 *
 * <p>Buildings are NOT stored here: only lightweight ids referencing the shared
 * {@code WorldStructureIndex}.
 */
public class ChunkMemory {

    /** Positions are packed into 16 bits: localX(4) | localZ(4) | y(8). */
    private static final int NO_ANCHOR = 0xFFFF;

    public long chunkKey;                 // ChunkPos.asLong(cx, cz)
    public long lastVisitedTick = 0;      // last game time the villager entered this chunk
    public float saliency = 0f;           // 0..1 — eviction priority
    public long entitySeenTick = 0;       // when the entity counts were taken

    private int tagFlags;
    private int featureFlags;

    /**
     * Tick until which a <em>second-hand</em> ore fact is still re-tellable. 0 = either no ore
     * knowledge, or first-hand observation (the villager saw the ore itself). Set by
     * {@code GossipChannel} when a depletable-resource fact is borrowed; the owner may still
     * falsify it on arrival regardless of this value.
     */
    private long oreExpiryTick = 0L;

    public long getOreExpiryTick() { return oreExpiryTick; }
    public void setOreExpiryTick(long t) { this.oreExpiryTick = t; }

    /** Coarse block counts per BlockCategory ordinal (clamped). Lazily allocated. */
    private short[] counts;
    /** One packed representative position per ChunkFeature ordinal. Lazily allocated. */
    private short[] anchors;
    /** Entity counts per EntityCategory ordinal (clamped to 127). Lazily allocated. */
    private byte[] entityCounts;
    /** Ids of buildings (from WorldStructureIndex) the villager knows about here. */
    private long[] knownBuildingIds = EMPTY_IDS;

    private static final long[] EMPTY_IDS = new long[0];

    public ChunkMemory() {}

    public ChunkMemory(long chunkKey) {
        this.chunkKey = chunkKey;
    }

    public int getX() { return ChunkPos.getX(chunkKey); }
    public int getZ() { return ChunkPos.getZ(chunkKey); }

    // ── Tags ─────────────────────────────────────────────────────────────

    public boolean has(ChunkTag tag)   { return (tagFlags & tag.bit()) != 0; }
    public void add(ChunkTag tag)      { tagFlags |= tag.bit(); }
    public void remove(ChunkTag tag)   { tagFlags &= ~tag.bit(); }
    public int getTagFlags()           { return tagFlags; }

    /** Labels for prompt text / debugging. Allocated on demand only. */
    public List<String> tagLabels() {
        List<String> out = new ArrayList<>(4);
        for (ChunkTag t : ChunkTag.values()) if (has(t)) out.add(t.label());
        return out;
    }

    // ── Features (one anchor position each) ───────────────────────────────

    public boolean has(ChunkFeature f) { return (featureFlags & f.bit()) != 0; }
    public void forgetFeature(ChunkFeature feature) {
        featureFlags &= ~feature.bit();
        if (anchors != null) anchors[feature.ordinal()] = (short) NO_ANCHOR;
    }
    public int getFeatureFlags()       { return featureFlags; }
    public boolean hasAnyOre()         { return (featureFlags & ORE_MASK) != 0; }

    private static final int ORE_MASK = buildOreMask();

    private static int buildOreMask() {
        int m = 0;
        for (ChunkFeature f : ChunkFeature.values()) if (f.isOre()) m |= f.bit();
        return m;
    }

    /**
     * Remember that {@code f} exists in this chunk. Only the first sighting keeps its
     * position — later ones just re-assert the bit, so cost is O(1) regardless of how many
     * matching blocks the chunk holds.
     *
     * @param localX 0..15 relative to the chunk origin
     * @param localZ 0..15 relative to the chunk origin
     */
    public void recordFeature(ChunkFeature f, int localX, int y, int localZ) {
        if (has(f)) return;
        featureFlags |= f.bit();
        if (anchors == null) {
            anchors = new short[ChunkFeature.values().length];
            for (int i = 0; i < anchors.length; i++) anchors[i] = (short) NO_ANCHOR;
        }
        anchors[f.ordinal()] = pack(localX, y, localZ);
    }

    /** Global position of the remembered representative block, or null if unknown. */
    public BlockPos anchorOf(ChunkFeature f) {
        if (anchors == null || !has(f)) return null;
        int v = anchors[f.ordinal()] & 0xFFFF;
        if (v == NO_ANCHOR) return null;
        int lx = (v >>> 12) & 15;
        int lz = (v >>> 8) & 15;
        int y = v & 0xFF;
        return new BlockPos((getX() << 4) + lx, y, (getZ() << 4) + lz);
    }

    private static short pack(int localX, int y, int localZ) {
        int yy = y < 0 ? 0 : (y > 255 ? 255 : y);
        return (short) (((localX & 15) << 12) | ((localZ & 15) << 8) | yy);
    }

    // ── Block category counts ────────────────────────────────────────────

    public int count(BlockCategory c) {
        return counts == null ? 0 : counts[c.ordinal()];
    }

    public void addCount(BlockCategory c, int n) {
        if (counts == null) counts = new short[BlockCategory.values().length];
        int v = counts[c.ordinal()] + n;
        counts[c.ordinal()] = (short) Math.min(v, Short.MAX_VALUE);
    }

    // ── Entity counts (no positions: they are stale by the next tick) ─────

    public int entityCount(EntityCategory c) {
        return entityCounts == null ? 0 : entityCounts[c.ordinal()] & 0xFF;
    }

    public void addEntity(EntityCategory c) {
        if (entityCounts == null) entityCounts = new byte[EntityCategory.values().length];
        int v = (entityCounts[c.ordinal()] & 0xFF) + 1;
        entityCounts[c.ordinal()] = (byte) Math.min(v, 255);
    }

    public void clearEntities() { entityCounts = null; }

    // ── Known buildings (ids into the shared index) ───────────────────────

    public long[] getKnownBuildingIds() { return knownBuildingIds; }

    public void addKnownBuilding(long id) {
        for (long existing : knownBuildingIds) if (existing == id) return;
        long[] grown = new long[knownBuildingIds.length + 1];
        System.arraycopy(knownBuildingIds, 0, grown, 0, knownBuildingIds.length);
        grown[knownBuildingIds.length] = id;
        knownBuildingIds = grown;
    }

    // ── NBT ──────────────────────────────────────────────────────────────

    public CompoundNBT writeNBT() {
        CompoundNBT n = new CompoundNBT();
        n.putLong("key", chunkKey);
        n.putLong("tick", lastVisitedTick);
        n.putFloat("sal", saliency);
        n.putInt("tag", tagFlags);
        if (oreExpiryTick != 0) n.putLong("oreexp", oreExpiryTick);
        if (featureFlags != 0) {
            n.putInt("feat", featureFlags);
            // dense int array: (featureOrdinal << 16) | packedPos — one int per present feature
            List<Integer> packed = new ArrayList<>();
            for (ChunkFeature f : ChunkFeature.values()) {
                if (!has(f)) continue;
                int v = anchors == null ? NO_ANCHOR : (anchors[f.ordinal()] & 0xFFFF);
                packed.add((f.ordinal() << 16) | v);
            }
            int[] arr = new int[packed.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = packed.get(i);
            n.putIntArray("anc", arr);
        }
        if (counts != null) {
            int[] arr = new int[counts.length];
            for (int i = 0; i < arr.length; i++) arr[i] = counts[i];
            n.putIntArray("cnt", arr);
        }
        if (entityCounts != null) {
            n.putByteArray("ent", entityCounts.clone());
            n.putLong("etick", entitySeenTick);
        }
        if (knownBuildingIds.length > 0) n.putLongArray("bld", knownBuildingIds.clone());
        return n;
    }

    public static ChunkMemory readNBT(CompoundNBT n) {
        ChunkMemory cm = new ChunkMemory(n.getLong("key"));
        cm.lastVisitedTick = n.getLong("tick");
        cm.saliency = n.getFloat("sal");
        cm.tagFlags = n.getInt("tag");
        cm.featureFlags = n.getInt("feat");
        cm.oreExpiryTick = n.getLong("oreexp");

        if (n.contains("anc")) {
            int[] arr = n.getIntArray("anc");
            if (arr.length > 0) {
                cm.anchors = new short[ChunkFeature.values().length];
                for (int i = 0; i < cm.anchors.length; i++) cm.anchors[i] = (short) NO_ANCHOR;
                ChunkFeature[] all = ChunkFeature.values();
                for (int v : arr) {
                    int ord = v >>> 16;
                    if (ord < all.length) cm.anchors[ord] = (short) (v & 0xFFFF);
                }
            }
        }
        if (n.contains("cnt")) {
            int[] arr = n.getIntArray("cnt");
            cm.counts = new short[BlockCategory.values().length];
            for (int i = 0; i < arr.length && i < cm.counts.length; i++) {
                cm.counts[i] = (short) Math.min(arr[i], Short.MAX_VALUE);
            }
        }
        if (n.contains("ent")) {
            byte[] arr = n.getByteArray("ent");
            cm.entityCounts = new byte[EntityCategory.values().length];
            System.arraycopy(arr, 0, cm.entityCounts, 0, Math.min(arr.length, cm.entityCounts.length));
            cm.entitySeenTick = n.getLong("etick");
        }
        if (n.contains("bld")) cm.knownBuildingIds = n.getLongArray("bld").clone();

        // ── Legacy (pre-slimming) format: string tag list + per-block observation lists.
        // Convert the tags, discard the block/entity spam.
        if (cm.tagFlags == 0 && n.contains("tags")) {
            ListNBT legacy = n.getList("tags", 8);
            for (int i = 0; i < legacy.size(); i++) {
                ChunkTag t = ChunkTag.byLabel(legacy.getString(i));
                if (t != null) cm.add(t);
            }
        }
        return cm;
    }
}
