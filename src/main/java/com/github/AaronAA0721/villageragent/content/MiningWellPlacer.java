package com.github.AaronAA0721.villageragent.content;

import com.github.AaronAA0721.villageragent.ai.VillageRegistry;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Runtime placer for mining wells inside villages.
 *
 * Selection: find a flat 4x4 surface spot near the workbench (same as before).
 * Road avoidance: after selecting, detect the nearest road (grass_path) direction
 * and shift the entire structure 3 blocks away from it in local coordinates.
 * Torch is inside the cobblestone wall, 2 blocks above a ladder column.
 * Well position is persisted into VillageRegistry for all miners to share.
 */
public final class MiningWellPlacer {

    private static final Logger LOG = LogManager.getLogger("MiningWellPlacer");
    private static final Map<ServerWorld, Set<Long>> placed = new IdentityHashMap<>();
    /** How many blocks to shift the structure away from the nearest road. */
    private static final int ROAD_OFFSET = 3;

    private MiningWellPlacer() {}

    public static void onWorldUnload(ServerWorld world) { placed.remove(world); }

    public static void ensureWell(ServerWorld world, BlockPos workbenchPos) {
        VillageRegistry registry = VillageRegistry.get(world);
        Long villageKey = registry.discover(world, workbenchPos);
        if (villageKey == null) return;

        Set<Long> worldPlaced = placed.computeIfAbsent(world, k -> new HashSet<>());
        if (worldPlaced.contains(villageKey)) return;

        if (registry.getWellPos(villageKey) != null) {
            worldPlaced.add(villageKey);
            return;
        }

        MutableBoundingBox bounds = registry.bounds(villageKey);
        if (bounds == null) return;

        if (hasExistingWell(world, bounds)) {
            worldPlaced.add(villageKey);
            return;
        }

        BlockPos placement = findPlacement(world, bounds, workbenchPos);
        if (placement == null) {
            LOG.debug("No suitable mining well position in village {} near {}", villageKey, workbenchPos);
            return;
        }

        // Detect nearest road and offset the structure away from it.
        placement = offsetAwayFromRoad(world, placement);

        placeWellBlocks(world, placement);
        worldPlaced.add(villageKey);

        // Persist well entry: ladder core at local (1,1)-(2,2), entry at (ox+1, groundY, oz+1).
        BlockPos wellEntry = new BlockPos(placement.getX() + 1, placement.getY(), placement.getZ() + 1);
        registry.setWellPos(villageKey, wellEntry);

        LOG.info("Placed mining well at {} (entry {}) in village {}", placement, wellEntry, villageKey);
    }

    // ── Road avoidance via local coordinate offset ───────────────────────

    /**
     * Scan around the 4x4 footprint for grass_path blocks. If found, shift the
     * placement position 3 blocks in the opposite direction (away from road).
     * If roads found on multiple sides, pick the closest one to dodge.
     */
    private static BlockPos offsetAwayFromRoad(ServerWorld world, BlockPos surface) {
        int ox = surface.getX(), oz = surface.getZ(), y = surface.getY();
        int scanR = 5; // how far to look for road

        // Accumulate a "road direction" vector.
        double rdx = 0, rdz = 0;
        int count = 0;
        for (int dx = -scanR; dx < 4 + scanR; dx++) {
            for (int dz = -scanR; dz < 4 + scanR; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (world.getBlockState(new BlockPos(ox + dx, y + dy, oz + dz)).getBlock() == Blocks.GRASS_PATH) {
                        // Direction from structure center (1.5, 1.5) to this road block.
                        rdx += (dx - 1.5);
                        rdz += (dz - 1.5);
                        count++;
                    }
                }
            }
        }

        if (count == 0) return surface; // no road nearby, no offset needed

        // Normalize and apply offset in the opposite direction.
        double len = Math.sqrt(rdx * rdx + rdz * rdz);
        if (len < 0.01) return surface;
        int shiftX = (int) Math.round(-rdx / len * ROAD_OFFSET);
        int shiftZ = (int) Math.round(-rdz / len * ROAD_OFFSET);

        // Re-check surface height at the shifted position.
        int newY = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox + shiftX, oz + shiftZ);
        return new BlockPos(ox + shiftX, newY, oz + shiftZ);
    }

    // ── Existing well detection ──────────────────────────────────────────

    private static boolean hasExistingWell(ServerWorld world, MutableBoundingBox bounds) {
        int scanStep = 4;
        for (int x = bounds.x0; x <= bounds.x1; x += scanStep) {
            for (int z = bounds.z0; z <= bounds.z1; z += scanStep) {
                if (!world.hasChunk(x >> 4, z >> 4)) continue;
                int surfaceY = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos below = new BlockPos(x, surfaceY - 1, z);
                if (world.getBlockState(below).getBlock() == Blocks.LADDER) {
                    int depth = 0;
                    for (int dy = 0; dy < 10; dy++)
                        if (world.getBlockState(below.below(dy)).getBlock() == Blocks.LADDER) depth++;
                    if (depth >= 5) return true;
                }
            }
        }
        return false;
    }

    // ── Placement selection (original simple logic) ──────────────────────

    /**
     * Find a flat 4x4 surface within the village bounds, not on top of buildings.
     * Road avoidance is handled separately via offsetAwayFromRoad().
     */
    private static BlockPos findPlacement(ServerWorld world, MutableBoundingBox bounds, BlockPos near) {
        Random rng = new Random(near.hashCode());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int attempt = 0; attempt < 40; attempt++) {
            int x = bounds.x0 + rng.nextInt(Math.max(1, bounds.x1 - bounds.x0 - 4));
            int z = bounds.z0 + rng.nextInt(Math.max(1, bounds.z1 - bounds.z0 - 4));
            if (!world.hasChunk(x >> 4, z >> 4)) continue;

            int surfaceY = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            boolean flat = true;
            for (int dx = 0; dx < 4 && flat; dx++) {
                for (int dz = 0; dz < 4 && flat; dz++) {
                    int y2 = world.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    if (Math.abs(y2 - surfaceY) > 1) flat = false;
                    BlockState below = world.getBlockState(new BlockPos(x + dx, surfaceY - 1, z + dz));
                    Block blk = below.getBlock();
                    if (blk == Blocks.OAK_PLANKS || blk == Blocks.COBBLESTONE
                            || blk instanceof DoorBlock || blk instanceof BedBlock) {
                        flat = false;
                    }
                }
            }
            if (!flat) continue;

            double dist = Math.sqrt(near.distSqr(new BlockPos(x, surfaceY, z)));
            double score = Math.abs(dist - 16);
            if (score < bestScore) { bestScore = score; best = new BlockPos(x, surfaceY, z); }
        }
        return best;
    }

    // ── Structure placement ──────────────────────────────────────────────

    /**
     * Place the mining well structure at the given surface origin.
     *
     * Layout (local coords):
     *   4x4 shaft with 2x2 ladder core at (1,1)-(2,2), 10 deep.
     *   Walls 2 high, door on south (dz=3).
     *   Torch inside north wall at (1, groundY+1, 1) — 2 blocks above ladder top.
     *   Roof = center 2x2 cobble. Front stairs outside door.
     */
    private static void placeWellBlocks(ServerWorld world, BlockPos surface) {
        int sx = 4, shaftDepth = 10;
        int groundY = surface.getY();
        int doorZ = 3;

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState ladderS = Blocks.LADDER.defaultBlockState()
                .setValue(LadderBlock.FACING, net.minecraft.util.Direction.SOUTH);
        BlockState ladderN = Blocks.LADDER.defaultBlockState()
                .setValue(LadderBlock.FACING, net.minecraft.util.Direction.NORTH);
        BlockState torchWall = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.block.WallTorchBlock.FACING, net.minecraft.util.Direction.SOUTH);
        BlockState door_ll = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, net.minecraft.util.Direction.SOUTH)
                .setValue(DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.RIGHT);
        BlockState door_lu = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.FACING, net.minecraft.util.Direction.SOUTH)
                .setValue(DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.RIGHT);
        BlockState door_rl = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, net.minecraft.util.Direction.SOUTH)
                .setValue(DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.LEFT);
        BlockState door_ru = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, net.minecraft.state.properties.DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.FACING, net.minecraft.util.Direction.SOUTH)
                .setValue(DoorBlock.HINGE, net.minecraft.state.properties.DoorHingeSide.LEFT);
        BlockState stairN = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(net.minecraft.block.StairsBlock.FACING, net.minecraft.util.Direction.NORTH)
                .setValue(net.minecraft.block.StairsBlock.HALF, net.minecraft.state.properties.Half.BOTTOM);
        BlockState air = Blocks.AIR.defaultBlockState();

        int ox = surface.getX(), oz = surface.getZ();

        // ── Shaft: ladders only underground ──
        for (int dy = 1; dy <= shaftDepth; dy++) {
            int y = groundY - dy;
            for (int dx = 0; dx < sx; dx++) for (int dz = 0; dz < sx; dz++)
                world.setBlock(new BlockPos(ox + dx, y, oz + dz), cobble, 2);
            world.setBlock(new BlockPos(ox + 1, y, oz + 1), ladderS, 2);
            world.setBlock(new BlockPos(ox + 2, y, oz + 1), ladderS, 2);
            world.setBlock(new BlockPos(ox + 1, y, oz + 2), ladderN, 2);
            world.setBlock(new BlockPos(ox + 2, y, oz + 2), ladderN, 2);
        }

        // ── Ground: cobble floor, shaft opening = air ──
        for (int dx = 0; dx < sx; dx++) for (int dz = 0; dz <= doorZ + 1; dz++)
            world.setBlock(new BlockPos(ox + dx, groundY, oz + dz), cobble, 2);
        world.setBlock(new BlockPos(ox + 1, groundY, oz + 1), air, 2);
        world.setBlock(new BlockPos(ox + 2, groundY, oz + 1), air, 2);
        world.setBlock(new BlockPos(ox + 1, groundY, oz + 2), air, 2);
        world.setBlock(new BlockPos(ox + 2, groundY, oz + 2), air, 2);

        // ── Walls: 2 high ──
        for (int dy = 1; dy <= 2; dy++) {
            int y = groundY + dy;
            for (int dx = 0; dx < sx; dx++) for (int dz = 0; dz <= doorZ; dz++)
                world.setBlock(new BlockPos(ox + dx, y, oz + dz), air, 2);
            for (int dx = 0; dx < sx; dx++) world.setBlock(new BlockPos(ox + dx, y, oz), cobble, 2);
            for (int dz = 1; dz < doorZ; dz++) world.setBlock(new BlockPos(ox, y, oz + dz), cobble, 2);
            for (int dz = 1; dz < doorZ; dz++) world.setBlock(new BlockPos(ox + sx - 1, y, oz + dz), cobble, 2);
        }

        // Door on south face
        world.setBlock(new BlockPos(ox + 1, groundY + 1, oz + doorZ), door_ll, 2);
        world.setBlock(new BlockPos(ox + 1, groundY + 2, oz + doorZ), door_lu, 2);
        world.setBlock(new BlockPos(ox + 2, groundY + 1, oz + doorZ), door_rl, 2);
        world.setBlock(new BlockPos(ox + 2, groundY + 2, oz + doorZ), door_ru, 2);

        // Torch: inside cobblestone wall, 2 blocks above ladder top (groundY-1).
        // Placed at (ox+1, groundY+1, oz+1), facing south, attached to north wall at oz.
        world.setBlock(new BlockPos(ox + 1, groundY + 1, oz + 1), torchWall, 2);

        // Roof: center 2x2 cobble
        world.setBlock(new BlockPos(ox + 1, groundY + 3, oz + 1), cobble, 2);
        world.setBlock(new BlockPos(ox + 2, groundY + 3, oz + 1), cobble, 2);
        world.setBlock(new BlockPos(ox + 1, groundY + 3, oz + 2), cobble, 2);
        world.setBlock(new BlockPos(ox + 2, groundY + 3, oz + 2), cobble, 2);

        // Front stairs
        world.setBlock(new BlockPos(ox + 1, groundY, oz + doorZ + 1), stairN, 2);
        world.setBlock(new BlockPos(ox + 2, groundY, oz + doorZ + 1), stairN, 2);
    }
}
