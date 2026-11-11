package com.github.AaronAA0721.villageragent.content;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntNBT;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.WorldGenRegistries;
import net.minecraft.world.gen.feature.jigsaw.JigsawPattern;
import net.minecraft.world.gen.feature.jigsaw.JigsawPiece;
import net.minecraft.world.gen.feature.jigsaw.SingleJigsawPiece;
import net.minecraft.world.gen.feature.template.ProcessorLists;
import net.minecraft.world.gen.feature.template.StructureProcessorList;
import net.minecraft.world.gen.feature.template.Template;
import net.minecraft.world.gen.feature.template.TemplateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Injects custom structures into vanilla village house pools (weighted random, same as vanilla).
 * Use /va dumpstructures to export .nbt files for manual editing.
 */
public final class VillageStructureInjector {

    private static final Logger LOGGER = LogManager.getLogger("VillagerStructures");
    private static final int WEIGHT = 2;

    private VillageStructureInjector() {}

    public static void inject() {
        String[] biomes = {"plains", "desert", "savanna", "snowy", "taiga"};
        for (String biome : biomes) {
            ResourceLocation poolId = new ResourceLocation("minecraft", "village/" + biome + "/houses");
            JigsawPattern pool = WorldGenRegistries.TEMPLATE_POOL.get(poolId);
            if (pool == null) { LOGGER.warn("Village house pool not found: {}", poolId); continue; }
            addToPool(pool, SingleJigsawPiece.single("villageragent:lumberjack_hut", ProcessorLists.EMPTY).apply(JigsawPattern.PlacementBehaviour.RIGID), WEIGHT);
            addToPool(pool, SingleJigsawPiece.single("villageragent:miner_hut", ProcessorLists.EMPTY).apply(JigsawPattern.PlacementBehaviour.RIGID), WEIGHT);
        }
        LOGGER.info("Injected lumberjack_hut and miner_hut into {} village house pools", biomes.length);
    }

    @SuppressWarnings("unchecked")
    private static void addToPool(JigsawPattern pool, JigsawPiece piece, int weight) {
        try {
            Field rawField = null, flatField = null;
            for (Field f : JigsawPattern.class.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                String generic = f.getGenericType().toString();
                if (generic.contains("Pair")) rawField = f;
                else if (generic.contains("JigsawPiece") || generic.contains("jigsaw")) flatField = f;
            }
            if (rawField == null || flatField == null) {
                List<Field> listFields = new ArrayList<>();
                for (Field f : JigsawPattern.class.getDeclaredFields())
                    if (List.class.isAssignableFrom(f.getType())) listFields.add(f);
                if (listFields.size() >= 2) for (Field f : listFields) {
                    f.setAccessible(true);
                    Object obj = f.get(pool);
                    if (obj instanceof List && !((List<?>) obj).isEmpty()) {
                        Object first = ((List<?>) obj).get(0);
                        if (first instanceof Pair) rawField = f;
                        else if (first instanceof JigsawPiece) flatField = f;
                    }
                }
            }
            if (rawField != null) {
                rawField.setAccessible(true);
                List<Pair<JigsawPiece, Integer>> raw = new ArrayList<>((List<Pair<JigsawPiece, Integer>>) rawField.get(pool));
                raw.add(Pair.of(piece, weight));
                rawField.set(pool, raw);
            }
            if (flatField != null) {
                flatField.setAccessible(true);
                List<JigsawPiece> flat = new ArrayList<>((List<JigsawPiece>) flatField.get(pool));
                for (int i = 0; i < weight; i++) flat.add(piece);
                flatField.set(pool, flat);
            }
        } catch (Exception e) { LOGGER.error("Failed to inject piece into pool: {}", e.getMessage()); }
    }

    // ── Template registration + export ──

    public static void registerTemplates(TemplateManager mgr) {
        registerTemplate(mgr, "villageragent:lumberjack_hut", buildLumberjackHut());
        // miner_hut: loaded from .nbt resource file (miner_hut_new), no code-gen needed.
        LOGGER.info("Registered lumberjack_hut (code-generated); miner_hut loaded from .nbt");
    }

    private static void registerTemplate(TemplateManager mgr, String id, CompoundNBT nbt) {
        try {
            Template template = new Template();
            template.load(nbt);
            for (Field f : TemplateManager.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<ResourceLocation, Template> repo = (Map<ResourceLocation, Template>) f.get(mgr);
                    repo.put(new ResourceLocation(id), template);
                    return;
                }
            }
        } catch (Exception e) { LOGGER.error("Failed to register template {}: {}", id, e.getMessage()); }
    }

    public static int exportNbt(File dir) {
        int count = 0;
        try {
            if (!dir.exists()) dir.mkdirs();
            Map<String, CompoundNBT> templates = new LinkedHashMap<>();
            templates.put("lumberjack_hut", buildLumberjackHut());
            // miner_hut uses .nbt resource file directly, not exported from code-gen
            for (Map.Entry<String, CompoundNBT> e : templates.entrySet()) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, e.getKey() + ".nbt"))) {
                    CompressedStreamTools.writeCompressed(e.getValue(), out);
                    count++;
                }
            }
            LOGGER.info("Exported structure .nbt files to {}", dir.getAbsolutePath());
        } catch (Exception e) { LOGGER.error("Failed to export .nbt files: {}", e.getMessage()); }
        return count;
    }

    // ── Structure builders ──

    /** Lumberjack hut: 7x5x7, oak plank walls, oak log pillars. */
    private static CompoundNBT buildLumberjackHut() {
        return buildWorkshop(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Workstations.CARPENTER_TABLE.get().defaultBlockState(), "minecraft:oak_planks");
    }

    /** Shared 7x5x8 workshop: 2-wide door (z=6), bed, workstation, torches, 2-step porch (z=7). */
    private static CompoundNBT buildWorkshop(BlockState wall, BlockState pillar,
                                              BlockState workstation, String jigsawFinal) {
        int sx = 7, sy = 5, sz = 8; // extra z row for the front steps
        HutBuilder b = new HutBuilder(sx, sy, sz);
        int doorZ = 6; // south wall (door)
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState torchS = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.block.WallTorchBlock.FACING, Direction.SOUTH);
        BlockState bed_head = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.block.BedBlock.PART, net.minecraft.state.properties.BedPart.HEAD)
                .setValue(net.minecraft.block.BedBlock.FACING, Direction.NORTH);
        BlockState bed_foot = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.block.BedBlock.PART, net.minecraft.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.block.BedBlock.FACING, Direction.NORTH);
        // Door facing SOUTH: left door (x=3) hinges RIGHT, right door (x=4) hinges LEFT.
        BlockState door_left_lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.block.DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.LOWER)
                .setValue(net.minecraft.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.block.DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.RIGHT);
        BlockState door_left_upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.block.DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.UPPER)
                .setValue(net.minecraft.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.block.DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.RIGHT);
        BlockState door_right_lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.block.DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.LOWER)
                .setValue(net.minecraft.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.block.DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.LEFT);
        BlockState door_right_upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.block.DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.UPPER)
                .setValue(net.minecraft.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.block.DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.LEFT);
        // Two steps in front of the door (outside, south side, z=7).
        BlockState stairN = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(net.minecraft.block.StairsBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.block.StairsBlock.HALF, net.minecraft.state.properties.Half.BOTTOM);

        b.fillLayer(0, wall);
        for (int y = 1; y <= 2; y++) {
            b.fillLayer(y, air);
            b.set(0, y, 0, pillar); b.set(sx-1, y, 0, pillar);
            b.set(0, y, doorZ, pillar); b.set(sx-1, y, doorZ, pillar);
            for (int x = 1; x < sx-1; x++) b.set(x, y, 0, wall);
            for (int x = 1; x < sx-1; x++) { if (x == 3 || x == 4) continue; b.set(x, y, doorZ, wall); }
            for (int z = 1; z < doorZ; z++) { b.set(0, y, z, wall); b.set(sx-1, y, z, wall); }
        }
        b.set(3, 1, doorZ, door_left_lower); b.set(3, 2, doorZ, door_left_upper);
        b.set(4, 1, doorZ, door_right_lower); b.set(4, 2, doorZ, door_right_upper);
        b.set(1, 1, 1, workstation);
        b.set(5, 1, 1, bed_head); b.set(5, 1, 2, bed_foot);
        b.set(2, 2, 1, torchS); b.set(4, 2, 1, torchS);
        b.fillLayer(3, wall);
        b.set(0, 3, 0, pillar); b.set(sx-1, 3, 0, pillar);
        b.set(0, 3, doorZ, pillar); b.set(sx-1, 3, doorZ, pillar);
        // Front steps (z=7) outside the door: two stairs under each door.
        b.set(3, 0, doorZ + 1, stairN);
        b.set(4, 0, doorZ + 1, stairN);
        b.setJigsaw(3, 0, doorZ, "south_up", "minecraft:building_entrance", "minecraft:street", jigsawFinal);
        return b.build(2586);
    }

    // Mine shaft is dug at runtime by miner AI, not pre-built in the structure template.

    // ── Grid-based NBT structure builder ──

    static final class HutBuilder {
        private final int sx, sy, sz;
        private final int[][][] grid;
        private final Map<String, Integer> paletteMap = new LinkedHashMap<>();
        private final List<CompoundNBT> jigsawNbt = new ArrayList<>();
        private final Set<Long> jigsawPositions = new HashSet<>();

        HutBuilder(int sx, int sy, int sz) {
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.grid = new int[sx][sy][sz];
            for (int[][] yz : grid) for (int[] zArr : yz) Arrays.fill(zArr, -1);
        }

        private int paletteIdx(BlockState state) {
            return paletteMap.computeIfAbsent(stateToNbtString(state), k -> paletteMap.size());
        }

        void set(int x, int y, int z, BlockState state) { grid[x][y][z] = paletteIdx(state); }

        void fillLayer(int y, BlockState state) {
            int idx = paletteIdx(state);
            for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) grid[x][y][z] = idx;
        }

        void setJigsaw(int x, int y, int z, String orientation, String name, String target, String finalState) {
            grid[x][y][z] = paletteMap.computeIfAbsent("minecraft:jigsaw[orientation=" + orientation + "]", k -> paletteMap.size());
            jigsawPositions.add(packPos(x, y, z));
            CompoundNBT te = new CompoundNBT();
            te.putString("name", name); te.putString("target", target);
            te.putString("pool", "minecraft:empty"); te.putString("final_state", finalState);
            te.putString("joint", "rollable"); te.putLong("_pos", packPos(x, y, z));
            jigsawNbt.add(te);
        }

        private static long packPos(int x, int y, int z) {
            return ((long) x << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF);
        }

        CompoundNBT build(int dataVersion) {
            CompoundNBT root = new CompoundNBT();
            root.putInt("DataVersion", dataVersion);
            ListNBT sizeList = new ListNBT();
            sizeList.add(IntNBT.valueOf(sx)); sizeList.add(IntNBT.valueOf(sy)); sizeList.add(IntNBT.valueOf(sz));
            root.put("size", sizeList);
            ListNBT palette = new ListNBT();
            for (String key : paletteMap.keySet()) palette.add(parseBlockNbt(key));
            root.put("palette", palette);
            ListNBT blockList = new ListNBT();
            for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
                int idx = grid[x][y][z];
                if (idx < 0) continue;
                CompoundNBT entry = new CompoundNBT();
                ListNBT pos = new ListNBT();
                pos.add(IntNBT.valueOf(x)); pos.add(IntNBT.valueOf(y)); pos.add(IntNBT.valueOf(z));
                entry.put("pos", pos); entry.putInt("state", idx);
                long pk = packPos(x, y, z);
                if (jigsawPositions.contains(pk)) for (CompoundNBT te : jigsawNbt)
                    if (te.getLong("_pos") == pk) { CompoundNBT c = te.copy(); c.remove("_pos"); entry.put("nbt", c); break; }
                blockList.add(entry);
            }
            root.put("blocks", blockList);
            root.put("entities", new ListNBT());
            return root;
        }

        private static CompoundNBT parseBlockNbt(String key) {
            CompoundNBT nbt = new CompoundNBT();
            int bracket = key.indexOf('[');
            if (bracket < 0) { nbt.putString("Name", key); }
            else {
                nbt.putString("Name", key.substring(0, bracket));
                CompoundNBT props = new CompoundNBT();
                for (String pair : key.substring(bracket + 1, key.length() - 1).split(",")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) props.putString(kv[0].trim(), kv[1].trim());
                }
                nbt.put("Properties", props);
            }
            return nbt;
        }

        private static String stateToNbtString(BlockState state) {
            ResourceLocation id = state.getBlock().getRegistryName();
            String name = id == null ? "minecraft:air" : id.toString();
            Collection<net.minecraft.state.Property<?>> props = state.getProperties();
            if (props.isEmpty()) return name;
            StringBuilder sb = new StringBuilder(name).append('[');
            boolean first = true;
            for (net.minecraft.state.Property<?> p : props) {
                if (!first) sb.append(',');
                sb.append(p.getName()).append('=').append(propertyValueName(state, p));
                first = false;
            }
            return sb.append(']').toString();
        }

        @SuppressWarnings("unchecked")
        private static <T extends Comparable<T>> String propertyValueName(BlockState state, net.minecraft.state.Property<T> prop) {
            return prop.getName(state.getValue(prop));
        }
    }
}
