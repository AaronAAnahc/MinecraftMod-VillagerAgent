package com.github.AaronAA0721.villageragent.ai.world;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.LongNBT;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A building located in the world by {@link BuildingLocator} / {@link WorldStructureIndex}.
 *
 * <p>At Stage 1 (coarse) this is produced with {@code detailed = false}: we only know a
 * rough bounding box, the seed bed, and the "seal radius" (narrowest opening inferred).
 * Stage 2 (full volumetric parse) would fill in rooms/doors/windows later.
 *
 * <p>A single building may be anchored by <b>more than one bed</b> (e.g. a house with two
 * beds, or a side-room bed whose detection lands inside an already-known house). All such
 * beds live in {@link #beds}; {@link #seedBed} is just the first one discovered and is used
 * as the stable {@link #id} source.
 */
public class BuildingRecord {
    public long id;
    public BlockPos seedBed;          // the first bed that anchored detection (stable id source)
    public BlockPos boundsMin;        // coarse AABB (expanded +1 in ±x/±z so the shell is enclosed)
    public BlockPos boundsMax;
    public float sealRadius;          // smallest erosion radius that sealed the cavity (= ~half the narrowest opening)
    public int volume;                // cavity air volume at seal radius
    public String coarseType;         // "house" / "cave_house" / "barn" / "unknown"
    public boolean detailed = false;  // Stage 2 sets this true

    /** Every bed that belongs to this building (the seed is always element 0). */
    public List<BlockPos> beds = new ArrayList<>();

    /**
     * Debug-only: the distance-field seeds (regional-maximum plateaus) of this building, with a
     * world position and whether each is an interior (room-candidate) or atmosphere seed. This is
     * transient debug data populated by {@code BuildingLocator.locateBed} and is NOT serialized to
     * NBT (it regenerates on re-detection). Never relied upon by gameplay logic.
     */
    public List<DebugSeed> debugSeeds = new ArrayList<>();

    /**
     * Debug-only: the watershed collection field at block resolution, for in-world visualization.
     * Each cell belongs to one collection (Voronoi region of a seed): atmospheric cells are flagged
     * so the client paints them with one uniform colour, non-atmospheric cells carry a distinct set
     * id for per-collection colouring, and boundary cells (the equal-geodesic-distance meeting
     * surface between two fronts) are flagged so they can be drawn separately. Transient debug data
     * — NOT serialized to NBT. Never relied upon by gameplay logic.
     */
    public List<DebugFieldCell> debugField = new ArrayList<>();

    /** A single distance-field seed, for in-world debug visualization. */
    public static class DebugSeed {
        public final BlockPos pos;
        /** true = interior / room-candidate (non-atmosphere) seed; false = atmosphere seed. */
        public final boolean interior;
        public DebugSeed(BlockPos pos, boolean interior) {
            this.pos = pos.immutable();
            this.interior = interior;
        }
    }

    /** A single watershed-collection cell (block resolution, world coordinates). */
    public static class DebugFieldCell {
        public final int wx, wy, wz;       // world block coordinates
        public final boolean atmospheric;  // true → uniform atmospheric colour
        public final boolean boundary;     // true → watershed boundary (excluded from collections)
        public final int setId;            // distinct colour id for non-atmospheric collections (>=0)
        public DebugFieldCell(int wx, int wy, int wz, boolean atmospheric, boolean boundary, int setId) {
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.atmospheric = atmospheric; this.boundary = boundary; this.setId = setId;
        }
    }

    public BuildingRecord() {}

    public BuildingRecord(long id, BlockPos seedBed, BlockPos boundsMin, BlockPos boundsMax,
                          float sealRadius, int volume, String coarseType) {
        this.id = id;
        this.seedBed = seedBed;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.sealRadius = sealRadius;
        this.volume = volume;
        this.coarseType = coarseType;
        this.beds.add(seedBed);
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= boundsMin.getX() && p.getX() <= boundsMax.getX()
            && p.getY() >= boundsMin.getY() && p.getY() <= boundsMax.getY()
            && p.getZ() >= boundsMin.getZ() && p.getZ() <= boundsMax.getZ();
    }

    public boolean hasBed(long bedLong) {
        for (BlockPos b : beds) if (b.asLong() == bedLong) return true;
        return false;
    }

    /** Bind another bed to this building (idempotent). Returns true if it was newly added. */
    public boolean addBed(BlockPos bed) {
        if (hasBed(bed.asLong())) return false;
        beds.add(bed.immutable());
        return true;
    }

    /** Remove a bed. Caller decides what to do if the list becomes empty. */
    public void removeBed(BlockPos bed) {
        long k = bed.asLong();
        beds.removeIf(b -> b.asLong() == k);
    }

    public CompoundNBT writeNBT() {
        CompoundNBT n = new CompoundNBT();
        n.putLong("id", id);
        n.putLong("bed", seedBed.asLong());
        n.putLong("min", boundsMin.asLong());
        n.putLong("max", boundsMax.asLong());
        n.putFloat("seal", sealRadius);
        n.putInt("vol", volume);
        n.putString("type", coarseType);
        n.putBoolean("det", detailed);
        ListNBT bedList = new ListNBT();
        for (BlockPos b : beds) bedList.add(LongNBT.valueOf(b.asLong()));
        n.put("beds", bedList);
        return n;
    }

    public static BuildingRecord readNBT(CompoundNBT n) {
        BuildingRecord r = new BuildingRecord();
        r.id = n.getLong("id");
        r.seedBed = BlockPos.of(n.getLong("bed"));
        r.boundsMin = BlockPos.of(n.getLong("min"));
        r.boundsMax = BlockPos.of(n.getLong("max"));
        r.sealRadius = n.getFloat("seal");
        r.volume = n.getInt("vol");
        r.coarseType = n.getString("type");
        r.detailed = n.getBoolean("det");
        r.beds.add(r.seedBed);
        if (n.contains("beds")) {
            ListNBT bl = n.getList("beds", 4); // 4 = NBT TAG_LONG
            for (int i = 0; i < bl.size(); i++) {
                BlockPos b = BlockPos.of(((LongNBT) bl.get(i)).getAsLong());
                if (!r.hasBed(b.asLong())) r.beds.add(b);
            }
        }
        return r;
    }
}
