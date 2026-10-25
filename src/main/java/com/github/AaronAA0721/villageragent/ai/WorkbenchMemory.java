package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.Blocks;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.nbt.*;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.*;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Dimension-qualified exact crafting-table memories, saved with the villager entity. */
public final class WorkbenchMemory {
    private static final String KEY = "VillagerAgentWorkbenches";
    public static void observe(VillagerEntity v, ServerWorld world) {
        ListNBT memories = v.getPersistentData().getList(KEY, 10);
        String dimension = world.dimension().location().toString();
        for (BlockPos p : BlockPos.betweenClosed(v.blockPosition().offset(-8, -3, -8), v.blockPosition().offset(8, 3, 8))) {
            if (!world.isLoaded(p) || !world.getBlockState(p).is(Blocks.CRAFTING_TABLE)) continue;
            BlockRayTraceResult hit = world.clip(new RayTraceContext(v.getEyePosition(1), Vector3d.atCenterOf(p),
                    RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, v));
            if (!hit.getBlockPos().equals(p)) continue;
            boolean known = false;
            for (int i = 0; i < memories.size(); i++) {
                CompoundNBT m = memories.getCompound(i);
                if (dimension.equals(m.getString("Dimension")) && m.getLong("Pos") == p.asLong()) { known = true; break; }
            }
            if (!known) {
                CompoundNBT m = new CompoundNBT(); m.putString("Dimension", dimension); m.putLong("Pos", p.asLong());
                if (memories.size() >= 64) memories.remove(0);
                memories.add(m);
            }
        }
        v.getPersistentData().put(KEY, memories);
    }

    /** Recall only loaded, still-existing tables, preferring reachable ones. */
    public static Path path(VillagerAgentData a, VillagerEntity v, ServerWorld world) {
        ListNBT memories = v.getPersistentData().getList(KEY, 10);
        String dimension = world.dimension().location().toString();
        Set<BlockPos> positions = new HashSet<>();
        for (int i = memories.size() - 1; i >= 0; i--) {
            CompoundNBT m = memories.getCompound(i);
            if (!dimension.equals(m.getString("Dimension"))) continue;
            BlockPos p = BlockPos.of(m.getLong("Pos"));
            if (!world.isLoaded(p)) continue;
            if (!world.getBlockState(p).is(Blocks.CRAFTING_TABLE)) { memories.remove(i); continue; }
            if (p.distSqr(v.blockPosition()) <= 128 * 128) positions.add(p);
        }
        v.getPersistentData().put(KEY, memories);
        if (positions.isEmpty()) return null;
        Path path = v.getNavigation().createPath(positions, 2);
        return path != null && path.getNodeCount() > 0 && path.canReach() ? path : null;
    }
    private WorkbenchMemory() {}
}
