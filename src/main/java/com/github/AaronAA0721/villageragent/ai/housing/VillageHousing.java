package com.github.AaronAA0721.villageragent.ai.housing;

import com.github.AaronAA0721.villageragent.ai.VillageRegistry;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.village.PointOfInterestManager;
import net.minecraft.village.PointOfInterestType;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Village housing facts for the Builder: how many beds and residents there are, where a new bed can go, and
 * where a new house fits.
 *
 * <p>Demand: {@code residents + builder_spare_beds - beds}. Keeping a spare bed matters because villagers only have
 * children when there are more beds than villagers ({@code VillagerBreeding}); without it a village would never
 * grow past its first houses.
 */
public final class VillageHousing {
    private VillageHousing() {}

    // ── Counting ────────────────────────────────────────────────────────────

    public static MutableBoundingBox bounds(ServerWorld w, long village) {
        return VillageRegistry.get(w).bounds(village);
    }

    private static int radius(MutableBoundingBox b) {
        return Math.max(b.x1 - b.x0, b.z1 - b.z0) / 2 + 16;
    }

    private static BlockPos center(MutableBoundingBox b) {
        return new BlockPos((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2, (b.z0 + b.z1) / 2);
    }

    private static boolean insideXZ(MutableBoundingBox b, int x, int z, int margin) {
        return x >= b.x0 - margin && x <= b.x1 + margin && z >= b.z0 - margin && z <= b.z1 + margin;
    }

    /** Bed (HOME) points of interest inside the village, claimed or not. */
    public static List<BlockPos> beds(ServerWorld w, long village) {
        MutableBoundingBox b = bounds(w, village);
        if (b == null) return Collections.emptyList();
        return w.getPoiManager().findAll(PointOfInterestType.HOME.getPredicate(),
                p -> insideXZ(b, p.getX(), p.getZ(), 8), center(b), radius(b), PointOfInterestManager.Status.ANY)
                .collect(Collectors.toList());
    }

    /** Living villagers (children included) inside the village. */
    public static int residents(ServerWorld w, long village) {
        MutableBoundingBox b = bounds(w, village);
        if (b == null) return 0;
        AxisAlignedBB box = new AxisAlignedBB(b.x0 - 8, 0, b.z0 - 8, b.x1 + 9, 256, b.z1 + 9);
        return w.getEntitiesOfClass(VillagerEntity.class, box, VillagerEntity::isAlive).size();
    }

    /** Beds still missing: residents + spare - beds (may be negative). */
    public static int demand(ServerWorld w, long village) {
        return residents(w, village) + ModConfig.BUILDER_SPARE_BEDS.get() - beds(w, village).size();
    }

    // ── Bed spots ───────────────────────────────────────────────────────────

    public static final class BedSpot {
        public final BlockPos foot;
        public final Direction facing;
        /** True for a slot of a house this mod built, false for a spare corner of an existing house. */
        public final boolean planned;
        BedSpot(BlockPos foot, Direction facing, boolean planned) {
            this.foot = foot.immutable(); this.facing = facing; this.planned = planned;
        }
        public BlockPos head() { return foot.relative(facing); }
    }

    /**
     * A free place for one more bed: first an empty bed slot of a house the builders finished, otherwise an
     * indoor spot near an existing bed (a second bed in a vanilla house). Null when there is none.
     */
    public static BedSpot freeBedSpot(ServerWorld w, long village) {
        ConstructionSites sites = ConstructionSites.get(w);
        for (ConstructionSites.Site site : sites.of(village)) {
            if (!site.finished) continue;
            HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, site.template, site.rotation);
            if (bp == null) continue;
            for (HouseBlueprints.BedSlot slot : bp.beds) {
                BlockPos foot = site.world(slot.foot);
                if (spotFree(w, foot, slot.facing)) return new BedSpot(foot, slot.facing, true);
            }
        }
        for (BlockPos bed : beds(w, village)) {
            if (!w.isLoaded(bed)) continue;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                BlockPos c = bed.offset(dx, 0, dz);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (spotFree(w, c, d) && indoors(w, c) && indoors(w, c.relative(d))
                            && !nearDoor(w, c) && !nearDoor(w, c.relative(d)) && bedsAround(w, c) < 2)
                        return new BedSpot(c, d, false);
                }
            }
        }
        return null;
    }

    /** Both bed cells and the room above them are empty, on a solid dry floor. */
    public static boolean spotFree(ServerWorld w, BlockPos foot, Direction facing) {
        BlockPos head = foot.relative(facing);
        for (BlockPos c : new BlockPos[]{foot, head}) {
            if (!w.isLoaded(c) || !w.isEmptyBlock(c) || !w.isEmptyBlock(c.above())) return false;
            BlockPos floor = c.below();
            if (!w.getBlockState(floor).isFaceSturdy(w, floor, Direction.UP)) return false;
            if (!w.getFluidState(c).isEmpty()) return false;
        }
        return true;
    }

    private static boolean indoors(ServerWorld w, BlockPos p) {
        return !w.canSeeSky(p);
    }

    private static boolean nearDoor(ServerWorld w, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (w.getBlockState(p.relative(d)).getBlock() instanceof DoorBlock) return true;
            if (w.getBlockState(p.relative(d).relative(d)).getBlock() instanceof DoorBlock) return true;
        }
        return false;
    }

    private static int bedsAround(ServerWorld w, BlockPos p) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockState s = w.getBlockState(p.offset(dx, 0, dz));
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == net.minecraft.state.properties.BedPart.HEAD) n++;
        }
        return n;
    }

    // ── Site search ─────────────────────────────────────────────────────────

    public static final class Candidate {
        public final String template;
        public final Rotation rotation;
        public final BlockPos origin;
        Candidate(String template, Rotation rotation, BlockPos origin) {
            this.template = template; this.rotation = rotation; this.origin = origin.immutable();
        }
    }

    /**
     * Incremental search for a flat, open spot inside the village (a few hundred checks per tick at most), nearest
     * the centre first. Each candidate tries a few house templates of the village's style in all four rotations.
     */
    public static final class SiteSearch {
        private final long village;
        private final MutableBoundingBox bounds;
        private final List<String> templates;
        private final Iterator<BlockPos> columns;
        private final List<MutableBoundingBox> taken = new ArrayList<>();
        private final Random random;
        private Candidate found;
        private boolean done;

        SiteSearch(ServerWorld w, long village, MutableBoundingBox bounds, List<String> templates, Random random) {
            this.village = village; this.bounds = bounds; this.templates = templates; this.random = random;
            BlockPos c = center(bounds);
            List<BlockPos> cols = new ArrayList<>();
            for (int x = bounds.x0; x <= bounds.x1; x += 2)
                for (int z = bounds.z0; z <= bounds.z1; z += 2) {
                    int dx = x - c.getX(), dz = z - c.getZ();
                    if (dx * dx + dz * dz >= 36) cols.add(new BlockPos(x, 0, z)); // keep clear of the meeting point
                }
            cols.sort(Comparator.comparingInt(p -> (p.getX() - c.getX()) * (p.getX() - c.getX())
                    + (p.getZ() - c.getZ()) * (p.getZ() - c.getZ())));
            this.columns = cols.iterator();
            ConstructionSites sites = ConstructionSites.get(w);
            for (ConstructionSites.Site s : sites.of(village)) {
                HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, s.template, s.rotation);
                if (bp != null) taken.add(s.box(bp, 2));
            }
        }

        /** Run up to {@code budget} fit checks. True once the search is over ({@link #result()} may be null). */
        public boolean step(ServerWorld w, int budget) {
            while (!done && budget > 0) {
                if (!columns.hasNext()) { done = true; break; }
                BlockPos col = columns.next();
                List<Rotation> rotations = new ArrayList<>(Arrays.asList(Rotation.values()));
                Collections.shuffle(rotations, random);
                for (String id : templates) {
                    for (Rotation rot : rotations) {
                        budget--;
                        HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, id, rot);
                        if (bp == null) continue;
                        BlockPos origin = fits(w, col, bp);
                        if (origin != null) {
                            found = new Candidate(id, rot, origin);
                            done = true;
                            return true;
                        }
                    }
                }
            }
            return done;
        }

        public Candidate result() { return found; }

        /**
         * Origin (ground layer) for {@code bp} with its corner at {@code corner}, or null when it does not fit:
         * the footprint must lie inside the village, on dry ground at most one block lower than the site level
         * (never higher: the builder does not dig), off roads and fields, with the whole volume and a one-block
         * ring around it empty apart from grass, flowers or snow.
         */
        private BlockPos fits(ServerWorld w, BlockPos corner, HouseBlueprints.Blueprint bp) {
            int x0 = corner.getX(), z0 = corner.getZ();
            if (!insideXZ(bounds, x0, z0, 0) || !insideXZ(bounds, x0 + bp.sizeX - 1, z0 + bp.sizeZ - 1, 0)) return null;
            BlockPos mid = new BlockPos(x0 + bp.sizeX / 2, 0, z0 + bp.sizeZ / 2);
            if (!w.hasChunk(mid.getX() >> 4, mid.getZ() >> 4)) return null;
            int g = w.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mid).getY(); // first free cell
            MutableBoundingBox box = new MutableBoundingBox(x0 - 2, g - 2, z0 - 2,
                    x0 + bp.sizeX + 1, g + bp.sizeY + 1, z0 + bp.sizeZ + 1);
            for (MutableBoundingBox t : taken) if (t.intersects(box)) return null;
            for (int x = x0 - 1; x <= x0 + bp.sizeX; x++) {
                for (int z = z0 - 1; z <= z0 + bp.sizeZ; z++) {
                    if (!w.hasChunk(x >> 4, z >> 4)) return null;
                    boolean foot = x >= x0 && x < x0 + bp.sizeX && z >= z0 && z < z0 + bp.sizeZ;
                    int h = w.getHeightmapPos(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
                    if (foot ? (h < g - 1 || h > g) : (h < g - 3 || h > g + 1)) return null;
                    BlockPos ground = new BlockPos(x, h - 1, z);
                    BlockState gs = w.getBlockState(ground);
                    if (!w.getFluidState(ground).isEmpty() || gs.getMaterial().isLiquid()) return null;
                    if (foot && !groundOk(gs)) return null;
                    if (!foot && PointOfInterestType.forState(gs).isPresent()) return null;
                    int top = foot ? g + bp.sizeY : g + 3;
                    for (int y = h; y < top; y++) {
                        BlockState s = w.getBlockState(new BlockPos(x, y, z));
                        if (!s.isAir() && !(s.getMaterial().isReplaceable() && s.getFluidState().isEmpty())) return null;
                    }
                }
            }
            return new BlockPos(x0, g - 1, z0);
        }

        private static boolean groundOk(BlockState s) {
            if (s.getBlock() == Blocks.GRASS_PATH || s.getBlock() == Blocks.FARMLAND) return false;   // roads, fields
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.BEDS)) return false;
            if (PointOfInterestType.forState(s).isPresent()) return false;                           // workstations
            return s.getMaterial().isSolid();
        }
    }

    /** A new search for the village, or null when the village has no known bounds or no usable templates. */
    public static SiteSearch newSearch(ServerWorld w, long village, Random random) {
        MutableBoundingBox b = bounds(w, village);
        if (b == null) return null;
        List<String> all = new ArrayList<>(HouseBlueprints.templates(w, HouseBlueprints.style(w, center(b))));
        if (all.isEmpty()) return null;
        Collections.shuffle(all, random);
        // A few designs per search keep it cheap; the next search may pick others.
        return new SiteSearch(w, village, b, all.subList(0, Math.min(3, all.size())), random);
    }

    /** Remove grass, flowers and snow from the cells the house will occupy. */
    public static void clearSite(ServerWorld w, ConstructionSites.Site site, HouseBlueprints.Blueprint bp) {
        List<BlockPos> cells = new ArrayList<>(bp.keepClear);
        for (HouseBlueprints.Step s : bp.steps) if (s.rel.getY() >= 1) cells.add(s.rel);
        for (BlockPos rel : cells) {
            BlockPos p = site.world(rel);
            BlockState s = w.getBlockState(p);
            if (!s.isAir() && s.getMaterial().isReplaceable() && s.getFluidState().isEmpty())
                w.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // ── Report (/va housing) ────────────────────────────────────────────────

    public static List<String> report(ServerWorld w, BlockPos pos) {
        List<String> out = new ArrayList<>();
        Long village = VillageRegistry.get(w).discover(w, pos);
        if (village == null) {
            out.add("Not inside a known village.");
            return out;
        }
        int beds = beds(w, village).size(), residents = residents(w, village);
        out.add("Village " + village + ": " + residents + " villagers, " + beds + " beds, spare target "
                + ModConfig.BUILDER_SPARE_BEDS.get() + " -> " + Math.max(0, demand(w, village)) + " bed(s) needed");
        BedSpot spot = freeBedSpot(w, village);
        out.add(spot == null ? "No free bed spot: a builder will build a house when beds are needed"
                : "Next bed goes to " + spot.foot.getX() + "," + spot.foot.getY() + "," + spot.foot.getZ()
                  + (spot.planned ? " (new house)" : " (existing house)"));
        for (ConstructionSites.Site s : ConstructionSites.get(w).of(village)) {
            HouseBlueprints.Blueprint bp = HouseBlueprints.get(w, s.template, s.rotation);
            if (bp == null) { out.add("Site " + s.template + ": plan unavailable"); continue; }
            int done = 0;
            Map<Item, Integer> missing = new LinkedHashMap<>();
            for (HouseBlueprints.Step st : bp.steps) {
                if (w.getBlockState(s.world(st.rel)).getBlock() == st.state.getBlock()) done++;
                else if (!st.free()) missing.merge(st.item, 1, Integer::sum);
            }
            StringBuilder line = new StringBuilder(s.finished ? "Built " : "Building ")
                    .append(bp.shortName()).append(" at ").append(s.origin.getX()).append(",").append(s.origin.getY())
                    .append(",").append(s.origin.getZ()).append(" — ").append(done).append("/").append(bp.steps.size())
                    .append(" blocks, ").append(bp.beds.size()).append(" bed slot(s)");
            if (!s.finished && !missing.isEmpty()) {
                line.append("; still to place:");
                int shown = 0;
                for (Map.Entry<Item, Integer> e : missing.entrySet()) {
                    if (shown++ >= 6) { line.append(" ..."); break; }
                    line.append(' ').append(e.getValue()).append("x ").append(e.getKey().getRegistryName().getPath());
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
