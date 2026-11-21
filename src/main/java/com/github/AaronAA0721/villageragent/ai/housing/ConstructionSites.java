package com.github.AaronAA0721.villageragent.ai.housing;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;

import java.util.*;

/**
 * Houses the builders of each village have marked out (in progress) or finished. Saved with the world, so a
 * half-built house is picked up again after a restart by whichever builder of that village works next.
 *
 * <p>Only the plan is stored (template id, rotation, origin). Progress is never stored: the world itself is the
 * progress — a step is done when its cell already holds the planned block.
 */
public final class ConstructionSites extends WorldSavedData {
    private static final String NAME = "villageragent_construction";

    public static final class Site {
        public final UUID id;
        public final long village;
        public final String template;
        public final Rotation rotation;
        /** World position of the template's (0,0,0) corner; y is the ground layer. */
        public final BlockPos origin;
        public final long startedTick;
        public boolean finished;
        public long finishedTick;

        Site(UUID id, long village, String template, Rotation rotation, BlockPos origin, long startedTick) {
            this.id = id; this.village = village; this.template = template; this.rotation = rotation;
            this.origin = origin.immutable(); this.startedTick = startedTick;
        }

        public BlockPos world(BlockPos rel) { return origin.offset(rel); }

        /** Footprint box (all heights) grown by {@code margin}. */
        public MutableBoundingBox box(HouseBlueprints.Blueprint bp, int margin) {
            return new MutableBoundingBox(origin.getX() - margin, origin.getY() - 1, origin.getZ() - margin,
                    origin.getX() + bp.sizeX - 1 + margin, origin.getY() + bp.sizeY + 1, origin.getZ() + bp.sizeZ - 1 + margin);
        }
    }

    private final List<Site> sites = new ArrayList<>();

    public ConstructionSites() { super(NAME); }

    public static ConstructionSites get(ServerWorld world) {
        return world.getDataStorage().computeIfAbsent(ConstructionSites::new, NAME);
    }

    /** The unfinished house of a village, if any (a village builds one house at a time). */
    public Site active(long village) {
        for (Site s : sites) if (s.village == village && !s.finished) return s;
        return null;
    }

    public List<Site> of(long village) {
        List<Site> out = new ArrayList<>();
        for (Site s : sites) if (s.village == village) out.add(s);
        return out;
    }

    public int finishedCount(long village) {
        int n = 0;
        for (Site s : sites) if (s.village == village && s.finished) n++;
        return n;
    }

    public Site byId(UUID id) {
        for (Site s : sites) if (s.id.equals(id)) return s;
        return null;
    }

    public Site add(long village, String template, Rotation rotation, BlockPos origin, long now) {
        Site s = new Site(UUID.randomUUID(), village, template, rotation, origin, now);
        sites.add(s);
        setDirty();
        return s;
    }

    public void finish(Site site, long now) {
        site.finished = true;
        site.finishedTick = now;
        setDirty();
    }

    public void remove(Site site) {
        if (sites.remove(site)) setDirty();
    }

    @Override
    public void load(CompoundNBT nbt) {
        sites.clear();
        ListNBT list = nbt.getList("Sites", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT c = list.getCompound(i);
            try {
                Rotation rot = Rotation.valueOf(c.getString("Rotation"));
                Site s = new Site(c.getUUID("Id"), c.getLong("Village"), c.getString("Template"), rot,
                        BlockPos.of(c.getLong("Origin")), c.getLong("Started"));
                s.finished = c.getBoolean("Finished");
                s.finishedTick = c.getLong("FinishedTick");
                sites.add(s);
            } catch (IllegalArgumentException ignored) {
                // Corrupt entry: drop it rather than fail the whole file.
            }
        }
    }

    @Override
    public CompoundNBT save(CompoundNBT nbt) {
        ListNBT list = new ListNBT();
        for (Site s : sites) {
            CompoundNBT c = new CompoundNBT();
            c.putUUID("Id", s.id);
            c.putLong("Village", s.village);
            c.putString("Template", s.template);
            c.putString("Rotation", s.rotation.name());
            c.putLong("Origin", s.origin.asLong());
            c.putLong("Started", s.startedTick);
            c.putBoolean("Finished", s.finished);
            c.putLong("FinishedTick", s.finishedTick);
            list.add(c);
        }
        nbt.put("Sites", list);
        return nbt;
    }
}
