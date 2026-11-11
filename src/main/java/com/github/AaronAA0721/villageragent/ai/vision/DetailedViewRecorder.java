package com.github.AaronAA0721.villageragent.ai.vision;

import com.github.AaronAA0721.villageragent.ai.memory.BlockObservation;
import com.github.AaronAA0721.villageragent.ai.memory.BlockCategory;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.EntityCategory;
import com.github.AaronAA0721.villageragent.ai.memory.EntityObservation;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Near-layer vision: a live frustum scan of the blocks and entities directly in
 * front of the villager (design doc §6). This is the "detailed recording of the
 * blocks in my current view" the villager was missing — it complements the cheap
 * chunk-memory summary (far layer) and the shared building index (mid layer).
 *
 * <p>Cost is bounded by the view-cone volume: we test every voxel in a small
 * box around the villager with the O(1) cone test, then only fetch the block
 * state for voxels that actually fall inside the cone. It is invoked on demand
 * (chat / autonomous decision), so it stays cheap even with many villagers.
 */
public final class DetailedViewRecorder {

    public static final float SCAN_FOV = FrustumCuller.DEFAULT_FOV;
    /** Live scan is shorter than the culler default — we only care about what is right here. */
    public static final float SCAN_RANGE = 16f;
    /** Scan the eye height ± this many blocks vertically. */
    public static final int Y_HALF = 4;
    /** Cap the number of notable blocks reported per scan (keeps the prompt small). */
    private static final int MAX_BLOCKS = 8;

    private DetailedViewRecorder() {}

    /** Result of a live view scan. */
    public static class ViewSnapshot {
        public final List<BlockObservation> blocks = new ArrayList<>();
        public final List<EntityObservation> entities = new ArrayList<>();

        public boolean isEmpty() { return blocks.isEmpty() && entities.isEmpty(); }
    }

    /**
     * Scan the view cone around {@code villager} and return the notable blocks and
     * in-view entities it currently sees.
     */
    public static ViewSnapshot record(LivingEntity villager, World world) {
        ViewSnapshot snap = new ViewSnapshot();
        Vector3d eye = FrustumCuller.eyePosition(villager);
        Vector3d fwd = FrustumCuller.forwardVector(villager);

        BlockPos vp = villager.blockPosition();
        int r = (int) Math.ceil(SCAN_RANGE);
        int x0 = vp.getX() >> 4 << 4;
        int z0 = vp.getZ() >> 4 << 4;
        int yLo = vp.getY() - Y_HALF;
        int yHi = vp.getY() + Y_HALF;

        scan:
        for (int x = vp.getX() - r; x <= vp.getX() + r; x++) {
            for (int z = vp.getZ() - r; z <= vp.getZ() + r; z++) {
                for (int y = yLo; y <= yHi; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!FrustumCuller.isInView(eye, center(p), fwd, SCAN_FOV, SCAN_RANGE)) continue;
                    BlockState st = world.getBlockState(p);
                    BlockCategory cat = ChunkContentSampler.classify(st);
                    if (cat == null) continue;                       // air / ignored
                    ChunkFeature poi = ChunkContentSampler.featureOf(st);
                    // Not a notable POI in view; gravel is remembered for work only (it would crowd out the rest).
                    if (poi == null || poi == ChunkFeature.GRAVEL) continue;
                    // The cone test is geometry only; without this the villager would report
                    // ore and chests straight through walls and solid stone.
                    if (!hasLineOfSight(villager, world, eye, p)) continue;
                    BlockObservation bo = new BlockObservation(
                            new BlockPos(x - x0, y, z - z0),
                            st.getBlock().getRegistryName().toString(),
                            noteFor(cat, st));
                    snap.blocks.add(bo);
                    if (snap.blocks.size() >= MAX_BLOCKS) break scan;
                }
            }
        }

        // Entities within the view cone (live query)
        AxisAlignedBB box = new AxisAlignedBB(vp).inflate(SCAN_RANGE);
        for (Entity e : world.getEntities(villager, box)) {
            if (!FrustumCuller.isEntityInView(villager, e, SCAN_FOV, SCAN_RANGE)) continue;
            String id = e.getType().getRegistryName() != null
                    ? e.getType().getRegistryName().toString()
                    : e.getType().toString();
            snap.entities.add(new EntityObservation(
                    id, ChunkContentSampler.categorize(e), e.blockPosition(), world.getGameTime()));
        }
        return snap;
    }

    private static Vector3d center(BlockPos p) {
        return new Vector3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    /** True if the ray from the villager's eye to the block centre lands on that very block. */
    private static boolean hasLineOfSight(LivingEntity villager, World world, Vector3d eye, BlockPos p) {
        return world.clip(new RayTraceContext(eye, center(p),
                RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, villager))
                .getBlockPos().equals(p);
    }

    private static String noteFor(BlockCategory cat, BlockState st) {
        if (cat == BlockCategory.ORE) return "exposed ore";
        if (st.getBlock() == net.minecraft.block.Blocks.LAVA
                || st.getFluidState().is(FluidTags.LAVA)) return "lava (danger)";
        if (st.is(net.minecraft.tags.BlockTags.BEDS)) return "a bed";
        if (st.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)) return "a door";
        if (st.is(net.minecraft.tags.BlockTags.CROPS)) return "crops";
        if (st.getBlock() == net.minecraft.block.Blocks.CRAFTING_TABLE) return "a workbench";
        if (st.getBlock() == net.minecraft.block.Blocks.CHEST
                || st.getBlock() == net.minecraft.block.Blocks.BARREL) return "storage";
        return null;
    }
}
