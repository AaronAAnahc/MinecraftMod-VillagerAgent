package com.github.AaronAA0721.villageragent.ai.housing;

import com.github.AaronAA0721.villageragent.ai.VillageProducible;
import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.state.Property;
import net.minecraft.state.properties.BedPart;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.state.properties.DoubleBlockHalf;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockReader;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.template.Template;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * House plans for the Builder, taken from the vanilla village houses ({@code data/minecraft/structures/village/...}).
 *
 * <p>A vanilla template is loaded through the server's structure manager (so data-fixed, and replaceable by data
 * packs), then turned into an ordered list of placement {@link Step}s:
 * <ul>
 *   <li>air / structure voids / jigsaw blocks are not placed; air above the ground layer must be kept clear;</li>
 *   <li>beds are <b>not</b> placed with the house: each bed becomes a {@link BedSlot} that the builder fills with a
 *       crafted bed when the village needs one;</li>
 *   <li>the upper half of doors (and tall plants) is placed together with the lower half;</li>
 *   <li>terrain blocks (dirt, grass, paths, sand) are free;</li>
 *   <li>blocks the village can never produce ({@link VillageProducible}: glass, terracotta, sandstone...) are
 *       substituted: glass panes become fences, full blocks become the house's main material, small decorations
 *       (flower pots...) are left out.</li>
 * </ul>
 * Steps are ordered so the shell stands before anything hangs on it: full blocks bottom-up, then stairs, slabs,
 * fences, then attachments (torches, ladders, chests...), and the doors last so the builder can always walk out.
 * Ground layer (template y = 0) sits at the terrain surface, exactly like a jigsaw-placed village house.
 */
public final class HouseBlueprints {
    private static final Logger LOG = LogManager.getLogger("HouseBlueprints");

    /** Placement phases (lower first). */
    public static final int PHASE_SHELL = 0, PHASE_PARTIAL = 1, PHASE_ATTACHMENT = 2, PHASE_DOOR = 3;

    private static final Map<String, List<String>> STYLES = new LinkedHashMap<>();
    static {
        STYLES.put("plains", ids("plains", "plains_small_house_", 8, "plains_medium_house_", 2));
        STYLES.put("desert", ids("desert", "desert_small_house_", 8, "desert_medium_house_", 2));
        STYLES.put("savanna", ids("savanna", "savanna_small_house_", 8, "savanna_medium_house_", 2));
        STYLES.put("taiga", ids("taiga", "taiga_small_house_", 5, "taiga_medium_house_", 4));
        STYLES.put("snowy", ids("snowy", "snowy_small_house_", 8, "snowy_medium_house_", 3));
    }

    private static List<String> ids(String biome, String small, int smallCount, String medium, int mediumCount) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= smallCount; i++) out.add("minecraft:village/" + biome + "/houses/" + small + i);
        for (int i = 1; i <= mediumCount; i++) out.add("minecraft:village/" + biome + "/houses/" + medium + i);
        return out;
    }

    /** Terrain: placed for free (the builder digs/levels it, it does not carry it). */
    private static final Set<Block> FREE = new HashSet<>(Arrays.asList(
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.GRASS_PATH, Blocks.FARMLAND,
            Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.MYCELIUM));

    /** Largest footprint (either side) a builder takes on. */
    private static final int MAX_SIDE = 13;

    // ── Data ────────────────────────────────────────────────────────────────

    public static final class Step {
        public final BlockPos rel;
        public final BlockState state;
        /** Item consumed for this block; {@link Items#AIR} for free terrain. */
        public final Item item;
        public final int phase;
        Step(BlockPos rel, BlockState state, Item item, int phase) {
            this.rel = rel; this.state = state; this.item = item; this.phase = phase;
        }
        public boolean free() { return item == Items.AIR; }
        /** Steps are built group by group: one phase, one layer at a time. */
        public int group() { return phase * 1024 + rel.getY(); }
    }

    public static final class BedSlot {
        public final BlockPos foot;
        public final Direction facing;
        BedSlot(BlockPos foot, Direction facing) { this.foot = foot; this.facing = facing; }
    }

    public static final class Blueprint {
        public final String id;
        public final Rotation rotation;
        /** Rotated size. */
        public final int sizeX, sizeY, sizeZ;
        public final List<Step> steps;
        /** Cells above the ground layer that must end up empty (rooms, doorways, bed cells). */
        public final List<BlockPos> keepClear;
        public final List<BedSlot> beds;
        Blueprint(String id, Rotation rotation, int sx, int sy, int sz, List<Step> steps, List<BlockPos> keepClear,
                  List<BedSlot> beds) {
            this.id = id; this.rotation = rotation; this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
            this.steps = Collections.unmodifiableList(steps);
            this.keepClear = Collections.unmodifiableList(keepClear);
            this.beds = Collections.unmodifiableList(beds);
        }
        /** "plains_small_house_3" */
        public String shortName() { return id.substring(id.lastIndexOf('/') + 1); }
        /** Items still to be carried in, for the whole house. */
        public Map<Item, Integer> materials() {
            Map<Item, Integer> m = new LinkedHashMap<>();
            for (Step s : steps) if (!s.free()) m.merge(s.item, 1, Integer::sum);
            return m;
        }
    }

    private static final class Raw {
        final int sx, sy, sz;
        final List<BlockPos> pos = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();
        Raw(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }
    }

    private static final Map<String, Optional<Raw>> RAW = new HashMap<>();
    private static final Map<String, Optional<Blueprint>> PLANS = new HashMap<>();

    private HouseBlueprints() {}

    /** Forget cached plans (recipes / data packs changed). */
    public static synchronized void clear() { RAW.clear(); PLANS.clear(); }

    /** Village style for a position: plains, desert, savanna, taiga or snowy. */
    public static String style(ServerWorld world, BlockPos pos) {
        Biome.Category c = world.getBiome(pos).getBiomeCategory();
        switch (c) {
            case DESERT: return "desert";
            case SAVANNA: return "savanna";
            case TAIGA: return "taiga";
            case ICY: return "snowy";
            default: return "plains";
        }
    }

    /** Usable house templates (loadable, with at least one bed, small enough) for a style. */
    public static List<String> templates(ServerWorld world, String style) {
        List<String> out = new ArrayList<>();
        for (String id : STYLES.getOrDefault(style, STYLES.get("plains"))) {
            Blueprint bp = get(world, id, Rotation.NONE);
            if (bp != null && !bp.beds.isEmpty()) out.add(id);
        }
        if (out.isEmpty() && !"plains".equals(style)) return templates(world, "plains");
        return out;
    }

    /** The plan for {@code id} turned by {@code rotation}, or null if the template is missing or unusable. */
    public static synchronized Blueprint get(ServerWorld world, String id, Rotation rotation) {
        String key = id + "@" + rotation.name();
        Optional<Blueprint> cached = PLANS.get(key);
        if (cached != null) return cached.orElse(null);
        Blueprint bp = null;
        try {
            Raw raw = raw(world, id);
            if (raw != null && raw.sx <= MAX_SIDE && raw.sz <= MAX_SIDE) bp = build(world, id, raw, rotation);
        } catch (Exception e) {
            LOG.warn("Could not turn {} into a house plan: {}", id, e.toString());
        }
        PLANS.put(key, Optional.ofNullable(bp));
        return bp;
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private static Raw raw(ServerWorld world, String id) {
        Optional<Raw> cached = RAW.get(id);
        if (cached != null) return cached.orElse(null);
        Raw raw = null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Template template = rl == null ? null : world.getStructureManager().get(rl);
        if (template != null) {
            CompoundNBT nbt = template.save(new CompoundNBT());
            ListNBT size = nbt.getList("size", 3);
            ListNBT palette = nbt.contains("palette", 9) ? nbt.getList("palette", 10)
                    : nbt.getList("palettes", 9).isEmpty() ? new ListNBT() : nbt.getList("palettes", 9).getList(0);
            if (size.size() == 3 && !palette.isEmpty()) {
                List<BlockState> states = new ArrayList<>();
                for (int i = 0; i < palette.size(); i++) states.add(NBTUtil.readBlockState(palette.getCompound(i)));
                raw = new Raw(size.getInt(0), size.getInt(1), size.getInt(2));
                ListNBT blocks = nbt.getList("blocks", 10);
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundNBT b = blocks.getCompound(i);
                    ListNBT p = b.getList("pos", 3);
                    int idx = b.getInt("state");
                    if (p.size() != 3 || idx < 0 || idx >= states.size()) continue;
                    raw.pos.add(new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)));
                    raw.states.add(states.get(idx));
                }
            }
        }
        if (raw == null) LOG.debug("House template {} not available", id);
        RAW.put(id, Optional.ofNullable(raw));
        return raw;
    }

    // ── Plan building ───────────────────────────────────────────────────────

    private static Blueprint build(ServerWorld world, String id, Raw raw, Rotation rot) {
        BlockState main = mainMaterial(world, raw);
        boolean rotated = rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90;
        int sx = rotated ? raw.sz : raw.sx, sz = rotated ? raw.sx : raw.sz;
        List<Step> steps = new ArrayList<>();
        List<BlockPos> clear = new ArrayList<>();
        List<BedSlot> beds = new ArrayList<>();

        for (int i = 0; i < raw.pos.size(); i++) {
            BlockPos p = raw.pos.get(i);
            BlockPos rel = rotate(p, rot, raw.sx, raw.sz);
            BlockState state = raw.states.get(i).rotate(rot);
            Block block = state.getBlock();
            boolean above = p.getY() >= 1;

            if (state.isAir() || block == Blocks.STRUCTURE_VOID || block == Blocks.JIGSAW
                    || block instanceof StructureBlock) {
                if (above && block != Blocks.STRUCTURE_VOID) clear.add(rel);
                continue;
            }
            if (block instanceof BedBlock) {
                clear.add(rel);
                if (state.getValue(BedBlock.PART) == BedPart.FOOT)
                    beds.add(new BedSlot(rel, state.getValue(BedBlock.FACING)));
                continue;
            }
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                clear.add(rel);
                continue;
            }
            if (!state.getFluidState().isEmpty() && block instanceof FlowingFluidBlock) continue;
            if (FREE.contains(block)) {
                steps.add(new Step(rel, state, Items.AIR, PHASE_SHELL));
                continue;
            }
            Item item = block.asItem();
            if (item == Items.AIR) {
                if (above) clear.add(rel);
                continue;
            }
            if (!VillageProducible.producible(world, item)) {
                BlockState sub = substitute(world, state, main);
                if (sub == null) {
                    if (above) clear.add(rel);
                    continue;
                }
                state = sub;
                item = sub.getBlock().asItem();
            }
            steps.add(new Step(rel, state, item, phaseOf(state)));
        }
        steps.sort(Comparator.comparingInt(Step::group)
                .thenComparingInt(s -> s.rel.getX()).thenComparingInt(s -> s.rel.getZ()));
        return new Blueprint(id, rot, sx, raw.sy, sz, steps, clear, beds);
    }

    /** The most common producible full block of the template (walls / floor), oak planks if none. */
    private static BlockState mainMaterial(ServerWorld world, Raw raw) {
        Map<Block, Integer> counts = new HashMap<>();
        for (BlockState s : raw.states) {
            Block b = s.getBlock();
            if (s.isAir() || FREE.contains(b) || !s.isCollisionShapeFullBlock(EmptyBlockReader.INSTANCE, BlockPos.ZERO)) continue;
            Item item = b.asItem();
            if (item == Items.AIR || !VillageProducible.producible(world, item)) continue;
            if (s.hasProperty(BlockStateProperties.AXIS)) continue; // logs are pillars, not wall fill
            counts.merge(b, 1, Integer::sum);
        }
        Block best = Blocks.OAK_PLANKS;
        int n = 0;
        for (Map.Entry<Block, Integer> e : counts.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        return best.defaultBlockState();
    }

    /** Replacement for a block the village cannot make, or null to leave it out. */
    private static BlockState substitute(ServerWorld world, BlockState state, BlockState main) {
        Block block = state.getBlock();
        if (block instanceof PaneBlock) {
            // Windows: a fence keeps the opening closed and still lets light in.
            return VillageProducible.producible(world, Items.OAK_FENCE) ? Blocks.OAK_FENCE.defaultBlockState() : null;
        }
        if (state.isCollisionShapeFullBlock(EmptyBlockReader.INSTANCE, BlockPos.ZERO)) return main;
        return null;
    }

    private static int phaseOf(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof DoorBlock) return PHASE_DOOR;
        if (state.isCollisionShapeFullBlock(EmptyBlockReader.INSTANCE, BlockPos.ZERO)) return PHASE_SHELL;
        if (b instanceof StairsBlock || b instanceof SlabBlock || b instanceof FenceBlock || b instanceof WallBlock
                || b instanceof PaneBlock || b instanceof FenceGateBlock) return PHASE_PARTIAL;
        return PHASE_ATTACHMENT;
    }

    /** Rotate a template position inside its box, keeping the result in [0, size). */
    static BlockPos rotate(BlockPos p, Rotation rot, int sx, int sz) {
        int x = p.getX(), z = p.getZ();
        switch (rot) {
            case CLOCKWISE_90: return new BlockPos(sz - 1 - z, p.getY(), x);
            case CLOCKWISE_180: return new BlockPos(sx - 1 - x, p.getY(), sz - 1 - z);
            case COUNTERCLOCKWISE_90: return new BlockPos(z, p.getY(), sx - 1 - x);
            default: return p;
        }
    }

    /** {@code to}'s default state with every property it shares with {@code from} (axis, facing, half...). */
    public static BlockState copyProperties(BlockState from, Block to) {
        BlockState out = to.defaultBlockState();
        for (Property<?> p : from.getProperties()) if (out.hasProperty(p)) out = copy(out, from, p);
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState into, BlockState from, Property<T> p) {
        return into.setValue(p, from.getValue(p));
    }
}
